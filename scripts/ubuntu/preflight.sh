#!/usr/bin/env bash
# Read-only checks of a live BMS server before its first update with the new role layout (SELF_HOSTING.md section 15).
# Changes nothing: it only reads files, catalogs and command output, and writes only inside its own temporary directory.
#   sudo bash preflight.sh [--ref <branch, tag or commit>]
# Put db-roles.sh, rehearse.sh and nginx-site.sh next to it (same directory as update.sh, e.g. /root/bms-update). Prints
# one line per check: PASS, WARN (works, but look at it), INFO or FAIL with the reason; exits 1 if any check FAILs. Never
# prints a secret (server.env values are only tested, not shown).
# The last block runs `update.sh --rehearse --ref <ref>` (default: CORE_UPDATE_REF of server.env, else master): the role
# move, grants and migrations of the real update inside transactions that are rolled back (rehearse.sh). It changes
# nothing in PostgreSQL; it refreshes the root mirror of the repository, as an update would.
#
# For tests only: BMS_PREFLIGHT_PG=host:port (superuser "postgres" over TCP instead of runuser -u postgres),
# BMS_PREFLIGHT_CONFIG (server.env), BMS_PREFLIGHT_APP_DIR (/opt/bms), BMS_PREFLIGHT_SKIP_HOST=true (skips the host,
# Nginx, systemd and file checks that need a real install), BMS_PREFLIGHT_UPDATE_SH (the update.sh to rehearse with),
# BMS_PREFLIGHT_NO_REHEARSE=true (skips the rehearsal: a WARN says so).
set -uo pipefail
umask 077
# The locale this server gives its scripts (LANG from the session or /etc/default/locale), kept before this script fixes
# its own: it is shown and the validators' self-test runs under it.
SYSTEM_LOCALE_VARS=()
for variable in LANG LC_ALL LC_CTYPE LC_COLLATE; do
    [[ -n ${!variable:-} ]] && SYSTEM_LOCALE_VARS+=("$variable=${!variable}")
done
# Locale (scripts/ubuntu/tests/locale_test.sh): with the server's LANG (en_US.UTF-8 and others) bracket ranges such as
# [!-~] or [a-z], sort orders and case conversions do not mean plain ASCII. Everything below runs as in C for ASCII while
# text stays UTF-8 (plain C when C.UTF-8 is missing); the exact ASCII checks also set LC_ALL=C themselves (bms_ascii_match).
if [[ $(LC_ALL=C.UTF-8 locale charmap 2>/dev/null) == UTF-8 ]]; then export LC_ALL=C.UTF-8; else export LC_ALL=C; fi
unset LANGUAGE
# [[ =~ ]] with LC_ALL=C for this one test, whatever the caller's locale: [a-z], [0-9a-f] or [!-~] mean ASCII.
bms_ascii_match() { local LC_ALL=C; [[ $1 =~ $2 ]]; }

SELF_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REHEARSE_REF=${BMS_PREFLIGHT_REF:-}
while (( $# > 0 )); do
    case "$1" in
        --ref) [[ $# -ge 2 ]] || { printf '%s\n' '--ref requiere un valor.' >&2; exit 2; }; REHEARSE_REF=$2; shift 2 ;;
        *) printf 'Argumento desconocido: %s\n' "$1" >&2; exit 2 ;;
    esac
done
CONFIG_FILE=${BMS_PREFLIGHT_CONFIG:-/etc/bms/server.env}
APP_DIR=${BMS_PREFLIGHT_APP_DIR:-/opt/bms}
NGINX_SITE=/etc/nginx/sites-available/bms
WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT
FAILS=0
WARNS=0

report() {
    local status=$1 name=$2 reason=${3:-}
    printf '%-4s %s%s\n' "$status" "$name" "${reason:+ — $reason}"
    [[ $status != FAIL ]] || FAILS=$((FAILS + 1))
    [[ $status != WARN ]] || WARNS=$((WARNS + 1))
}

# The transport db-roles.sh needs: psql as the PostgreSQL superuser, in a read-only transaction, SQL on stdin. It adds
# nothing about output: bms_pg_query (db-roles.sh) adds -q -X -A -t and ON_ERROR_STOP for every check below.
bms_pg_transport() {
    local database=$1
    shift
    if [[ -n ${BMS_PREFLIGHT_PG:-} ]]; then
        PGOPTIONS='-c default_transaction_read_only=on' psql \
            -h "${BMS_PREFLIGHT_PG%:*}" -p "${BMS_PREFLIGHT_PG##*:}" -U postgres -d "$database" "$@"
    else
        runuser -u postgres -- env PGOPTIONS='-c default_transaction_read_only=on' psql -d "$database" "$@"
    fi
}
pg_read() { bms_pg_query "$@"; }

config_value() { awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$CONFIG_FILE"; }

[[ $EUID -eq 0 || -n ${BMS_PREFLIGHT_PG:-} ]] || { printf 'Ejecuta con sudo bash preflight.sh.\n' >&2; exit 2; }
for library in db-roles.sh nginx-site.sh; do
    if [[ -f $SELF_DIR/$library ]]; then
        # shellcheck source=/dev/null
        source "$SELF_DIR/$library"
    else
        report FAIL "biblioteca $library" "falta junto a preflight.sh (descargala de scripts/ubuntu/ de la misma referencia)"
        # Every PostgreSQL check goes through db-roles.sh.
        [[ $library != db-roles.sh ]] || { printf '\n%s FAIL, %s WARN\n' "$FAILS" "$WARNS"; exit 1; }
    fi
done

version_ge() { [[ $(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1) == "$2" ]]; }

# --- Host -------------------------------------------------------------------------------------------------------
if [[ ${BMS_PREFLIGHT_SKIP_HOST:-} != true ]]; then
    if command -v nginx >/dev/null 2>&1; then
        version=$(nginx -v 2>&1 | sed -nE 's#.*nginx/([0-9]+\.[0-9]+\.[0-9]+).*#\1#p')
        if version_ge "${version:-0}" 1.19.4; then
            report PASS 'Nginx' "$version (ssl_reject_handshake necesita 1.19.4)"
        else
            report FAIL 'Nginx' "${version:-desconocida}: hace falta 1.19.4 o posterior"
        fi
        if nginx -t >"$WORK/nginx-t" 2>&1; then report PASS 'nginx -t'; else report FAIL 'nginx -t' "$(tail -n 1 "$WORK/nginx-t")"; fi
    else
        report FAIL 'Nginx' 'no esta instalado'
    fi
    if command -v pg_lsclusters >/dev/null 2>&1; then
        online=$(pg_lsclusters --no-header 2>/dev/null | awk '$4 == "online"' | wc -l)
        if [[ $online == 1 ]]; then report PASS 'PostgreSQL' 'un cluster en linea'
        else report FAIL 'PostgreSQL' "$online clusters en linea (update.sh espera uno)"; fi
    else
        report WARN 'PostgreSQL' 'no hay pg_lsclusters para contar los clusters'
    fi
    arch=$(dpkg --print-architecture 2>/dev/null || echo amd64)
    if [[ -x /usr/lib/jvm/java-21-openjdk-$arch/bin/java ]]; then report PASS 'Java 21'
    else report FAIL 'Java 21' "no esta /usr/lib/jvm/java-21-openjdk-$arch (openjdk-21-jdk-headless)"; fi
    ram_kb=$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)
    if (( ram_kb >= 8 * 1024 * 1024 )); then report PASS 'Memoria' "$((ram_kb / 1024)) MiB (MemoryMax=80% por defecto)"
    elif (( ram_kb >= 3 * 1024 * 1024 )); then report PASS 'Memoria' "$((ram_kb / 1024)) MiB (sin MemoryMax por defecto)"
    else report WARN 'Memoria' "$((ram_kb / 1024)) MiB: la compilacion usara swap temporal; considera WEB_DIST_FROM_CI=true"; fi
    for setting in fs.protected_symlinks fs.protected_hardlinks; do
        value=$(sysctl -n "$setting" 2>/dev/null)
        if [[ $value == 1 ]]; then report PASS "$setting"; else report FAIL "$setting" "vale ${value:-?}; debe ser 1"; fi
    done
fi

# --- Locale -------------------------------------------------------------------------------------------------------
# The update that failed with "DB_PROVISIONING_PASSWORD debe tener al menos 16 caracteres ASCII imprimibles" ran under
# LANG=en_US.UTF-8, where [!-~] does not mean printable ASCII. The scripts now fix their locale; this shows the system's
# and runs update.sh's validators under it (bash update.sh --self-test), so that class of problem shows up here.
default_locale=$(sed -nE 's/^LANG="?([^"]*)"?$/\1/p' /etc/default/locale 2>/dev/null | head -n 1)
report INFO 'locale del sistema' "${SYSTEM_LOCALE_VARS[*]:-sin LANG ni LC_*} (/etc/default/locale: ${default_locale:-sin LANG})"
if env -u LANG -u LC_ALL -u LC_CTYPE -u LC_COLLATE ${SYSTEM_LOCALE_VARS[@]+"${SYSTEM_LOCALE_VARS[@]}"} \
    bash -c '[[ 0123456789abcdef0123456789abcdef =~ ^[!-~]{16,}$ && ! é =~ ^[a-z]$ ]]' 2>/dev/null; then # locale-lint: unprotected on purpose
    report PASS 'rangos sin proteccion' 'con este locale [!-~] y [a-z] son ASCII'
else
    report INFO 'rangos sin proteccion' 'con este locale [!-~] o [a-z] no son ASCII: los scripts de BMS fijan su propio locale (LC_ALL=C.UTF-8 / C)'
fi
if [[ -f $SELF_DIR/update.sh ]]; then
    if self_test=$(env -u LANG -u LC_ALL -u LC_CTYPE -u LC_COLLATE ${SYSTEM_LOCALE_VARS[@]+"${SYSTEM_LOCALE_VARS[@]}"} \
        bash "$SELF_DIR/update.sh" --self-test 2>&1); then
        report PASS 'validadores de update.sh' "$(grep -c '^PASS' <<< "$self_test") comprobaciones con el locale del sistema"
    else
        report FAIL 'validadores de update.sh' "$(grep -v '^PASS' <<< "$self_test" | head -n 5 | paste -sd ';' -)"
    fi
    # The same validators on the values of server.env (never printed).
    # shellcheck source=/dev/null
    source <(sed -n '/^# BEGIN validators/,/^# END validators/p' "$SELF_DIR/update.sh")
else
    report WARN 'validadores de update.sh' 'falta update.sh junto a preflight.sh: no se probaron'
fi

# --- server.env -------------------------------------------------------------------------------------------------
if [[ ! -r $CONFIG_FILE ]]; then
    report FAIL 'server.env' "no se puede leer $CONFIG_FILE"
    printf '\n%s FAIL, %s WARN\n' "$FAILS" "$WARNS"
    exit 1
fi
if [[ ${BMS_PREFLIGHT_SKIP_HOST:-} != true ]]; then
    mode=$(stat -c '%U:%G %a' -- "$CONFIG_FILE")
    if [[ $mode == 'root:root 600' ]]; then report PASS 'server.env permisos' "$mode"; else report FAIL 'server.env permisos' "$mode (debe ser root:root 600)"; fi
fi
missing=()
for key in DB_URL DB_USER DB_PASSWORD BMS_MASTER_KEY TENANT_JDBC_URL_PREFIX; do
    [[ -n $(config_value "$key") ]] || missing+=("$key")
done
if (( ${#missing[@]} == 0 )); then report PASS 'server.env claves' 'DB_URL, DB_USER, DB_PASSWORD, BMS_MASTER_KEY, TENANT_JDBC_URL_PREFIX presentes'
else report FAIL 'server.env claves' "faltan o estan vacias: ${missing[*]}"; fi
DB_URL=$(config_value DB_URL)
DB_USER=$(config_value DB_USER)
CONTROL_DB=''
if bms_ascii_match "$DB_URL" '^jdbc:postgresql://[A-Za-z0-9.-]+(:[0-9]{1,5})?/([a-z][a-z0-9_]{0,62})$'; then
    CONTROL_DB=${BASH_REMATCH[2]}
    report PASS 'DB_URL' "forma jdbc:postgresql://servidor:puerto/$CONTROL_DB"
else
    report FAIL 'DB_URL' 'no tiene la forma jdbc:postgresql://servidor:puerto/base (sin parametros)'
fi
if bms_ascii_match "$DB_USER" '^[a-z][a-z0-9_]{0,62}$' && [[ $DB_USER != pg_* && $DB_USER != postgres ]]; then report PASS 'DB_USER' "$DB_USER"
else report FAIL 'DB_USER' 'no es un nombre de rol valido'; fi
provisioner=$(config_value DB_PROVISIONING_USER)
if [[ -n $provisioner ]]; then report INFO 'DB_PROVISIONING_USER' "$provisioner (ya configurado)"
else report INFO 'DB_PROVISIONING_USER' 'no esta en server.env; update.sh crea bms_provisioner o, si ya existe como lo deja un intento anterior, lo adopta'; fi
if declare -F valid_provisioner_password >/dev/null && grep -q '^DB_PROVISIONING_PASSWORD=' "$CONFIG_FILE"; then
    provisioner_password=$(config_value DB_PROVISIONING_PASSWORD)
    provisioner_password=${provisioner_password#\"}
    provisioner_password=${provisioner_password%\"}
    if valid_provisioner_password "$provisioner_password"; then report PASS 'DB_PROVISIONING_PASSWORD' 'cumple la politica (16+ ASCII imprimibles, sin ":" ni "\")'
    else report FAIL 'DB_PROVISIONING_PASSWORD' 'no cumple la politica: al menos 16 caracteres ASCII imprimibles, sin ":" ni "\"'; fi
    unset provisioner_password
fi

# --- PostgreSQL ---------------------------------------------------------------------------------------------------
if ! pg_read postgres -c 'SELECT 1' >/dev/null 2>&1; then
    report FAIL 'conexion a PostgreSQL' 'no se pudo conectar como postgres'
    printf '\n%s FAIL, %s WARN\n' "$FAILS" "$WARNS"
    exit 1
fi
server_major=$(pg_read postgres -c "SELECT current_setting('server_version_num')::int / 10000")
dump_major=$(pg_dump --version 2>/dev/null | sed -nE 's/.* ([0-9]+)(\.[0-9]+)*.*/\1/p' | head -n 1)
if [[ $dump_major == "$server_major" ]]; then report PASS 'pg_dump' "version $dump_major, servidor $server_major"
else report FAIL 'pg_dump' "pg_dump $dump_major y servidor $server_major: la copia previa a la actualizacion fallaria o no serviria"; fi
if (( server_major >= 16 )); then report PASS 'PostgreSQL version' "$server_major (opciones de pertenencia SET/INHERIT)"
else report WARN 'PostgreSQL version' "$server_major: anterior a 16, la pertenencia sin herencia depende del atributo NOINHERIT"; fi

if [[ -n $CONTROL_DB ]] && [[ $(pg_read postgres -v db="$CONTROL_DB" <<< "SELECT count(*) FROM pg_database WHERE datname = :'db'") == 1 ]]; then
    report PASS 'base central' "$CONTROL_DB existe"
    all_owners=$(pg_read "$CONTROL_DB" <<'SQL'
SELECT u.postgres_role || '|' || COALESCE(r.rolsuper::text, 'missing') || '|' || COALESCE(r.rolcanlogin::text, '') || '|'
       || COALESCE(r.rolcreatedb::text, '') || '|' || COALESCE(r.rolcreaterole::text, '')
FROM public.server_owners o JOIN public.application_users u ON u.id = o.user_id
LEFT JOIN pg_catalog.pg_roles r ON r.rolname = u.postgres_role ORDER BY 1;
SQL
)
    if [[ -z $all_owners ]]; then report FAIL 'roles de server owner' 'no hay ninguno en server_owners'; fi
    while IFS='|' read -r role super login createdb createrole; do
        [[ -n $role ]] || continue
        if [[ $super == missing ]]; then report FAIL "server owner $role" 'el rol no existe en PostgreSQL'
        elif [[ $super == true || $role == pg_* ]]; then report FAIL "server owner $role" 'es superusuario o un rol del sistema'
        elif [[ $login == true && $createdb == true && $createrole == true ]]; then report PASS "server owner $role" 'LOGIN CREATEDB CREATEROLE'
        else report FAIL "server owner $role" "le falta LOGIN, CREATEDB o CREATEROLE (login=$login createdb=$createdb createrole=$createrole)"; fi
    done <<< "$all_owners"

    # Runtime role.
    runtime=$(pg_read postgres -v r="$DB_USER" <<'SQL'
SELECT rolsuper || '|' || rolcreatedb || '|' || rolcreaterole || '|' ||
       COALESCE((SELECT string_agg(pg_get_userbyid(m.roleid), ',') FROM pg_auth_members m WHERE m.member = r.oid), '')
FROM pg_roles r WHERE rolname = :'r';
SQL
)
    if [[ -z $runtime ]]; then report FAIL "rol $DB_USER" 'no existe'
    else
        IFS='|' read -r super createdb createrole memberships <<< "$runtime"
        if [[ $super == true || $createdb == true || $createrole == true ]]; then report FAIL "rol $DB_USER" 'tiene SUPERUSER, CREATEDB o CREATEROLE propios'
        elif [[ -n $memberships ]]; then report INFO "rol $DB_USER" "miembro de $memberships (update.sh quita la pertenencia a los roles de server owner)"
        else report PASS "rol $DB_USER" 'sin pertenencias ni atributos (organizacion nueva)'; fi
    fi

    # Company databases.
    classified=$(bms_classify_tenant_databases "$CONTROL_DB" 2>/dev/null)
    total_bytes=$(pg_read postgres <<< "SELECT COALESCE(sum(pg_database_size(oid)), 0) FROM pg_database WHERE NOT datistemplate")
    while read -r kind name rest; do
        [[ -n $kind ]] || continue
        case $kind in
            missing) report WARN "empresa $name" 'registrada en companies pero la base no existe (se omite)'; continue ;;
            reject) report FAIL "empresa $name" "$rest: update.sh no cambiara los roles hasta que se corrija" ; continue ;;
        esac
        owner=$rest
        report PASS "empresa $name" "nombre y dueno ($owner) correctos"
        detail=$(pg_read "$name" -v owner="$owner" -v runtime="$DB_USER" <<'SQL'
SELECT 'owners=' || COALESCE((SELECT string_agg(o || ':' || n, ',' ORDER BY o) FROM (
           SELECT pg_get_userbyid(c.relowner) AS o, count(*) AS n FROM pg_class c
           WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r','p','v','m','S','f') GROUP BY 1) x), '')
    || '|schemas=' || COALESCE((SELECT string_agg(nspname, ',') FROM pg_namespace
           WHERE nspname NOT IN ('public', 'information_schema') AND nspname !~ '^pg_'), '')
    || '|create=' || has_schema_privilege(:'owner', 'public', 'CREATE')
    || '|runtime_connect=' || has_database_privilege(:'runtime', current_database(), 'CONNECT');
SQL
) || { report FAIL "empresa $name" 'no se pudo leer su catalogo'; continue; }
        IFS='|' read -r owners_part schemas_part create_part connect_part <<< "$detail"
        report INFO "empresa $name duenos en public" "${owners_part#owners=} (update.sh da al dueno lo de postgres y del runtime)"
        if [[ -n ${schemas_part#schemas=} ]]; then report WARN "empresa $name esquemas" "esquemas propios: ${schemas_part#schemas=} (update.sh no los toca)"; fi
        if [[ ${create_part#create=} == true ]]; then report PASS "empresa $name CREATE en public" "$owner puede crear (migraciones como el dueno)"
        else report FAIL "empresa $name CREATE en public" "$owner no puede crear en public: las migraciones como el dueno fallarian"; fi
        if [[ ${connect_part#runtime_connect=} == true ]]; then report PASS "empresa $name CONNECT" "$DB_USER puede conectarse"
        else report FAIL "empresa $name CONNECT" "$DB_USER no puede conectarse"; fi
    done <<< "$classified"

    # Pending core updates.
    if [[ $(pg_read "$CONTROL_DB" <<< "SELECT to_regclass('public.core_update_requests') IS NOT NULL") == t ]]; then
        pending=$(pg_read "$CONTROL_DB" <<< "SELECT count(*) FROM public.core_update_requests WHERE status IN ('pending', 'running')")
        if [[ $pending == 0 ]]; then report PASS 'actualizaciones pedidas desde la app' 'ninguna pendiente'
        else report FAIL 'actualizaciones pedidas desde la app' "$pending pendientes o en curso: esperalas o cancelalas antes"; fi
    fi
else
    report FAIL 'base central' "${CONTROL_DB:-?} no existe"
    total_bytes=0
fi

# pg_hba: the provisioning login connects over TCP to 127.0.0.1 with a password.
hba=$(pg_read postgres <<'SQL' 2>/dev/null
SELECT type || '|' || COALESCE(address, '') || '|' || array_to_string(database, ',') || '|' || array_to_string(user_name, ',')
       || '|' || COALESCE(auth_method, '') || '|' || COALESCE(error, '')
FROM pg_hba_file_rules ORDER BY rule_number;
SQL
)
if [[ -z $hba ]]; then
    report WARN 'pg_hba' 'no se pudieron leer las reglas'
else
    if [[ -n $(awk -F'|' '$6 != ""' <<< "$hba") ]]; then report FAIL 'pg_hba' 'hay reglas con errores (pg_hba_file_rules.error)'; fi
    rule=$(awk -F'|' '($1 == "host" || $1 == "hostssl") && ($2 == "127.0.0.1" || $2 == "all" || $2 == "samehost") && $3 ~ /(^|,)all(,|$)/ && $4 ~ /(^|,)all(,|$)/ { print $5; exit }' <<< "$hba")
    case $rule in
        scram-sha-256) report PASS 'pg_hba 127.0.0.1' 'scram-sha-256 para todos' ;;
        md5) report WARN 'pg_hba 127.0.0.1' 'md5 (funciona con contrasenas SCRAM, pero mejor scram-sha-256)' ;;
        '') report FAIL 'pg_hba 127.0.0.1' 'ninguna regla host para 127.0.0.1 y todos los usuarios: el login de aprovisionamiento no podra conectarse' ;;
        *) report FAIL 'pg_hba 127.0.0.1' "metodo $rule (trust o password no son aceptables)" ;;
    esac
fi

# --- Disk -----------------------------------------------------------------------------------------------------
if [[ ${BMS_PREFLIGHT_SKIP_HOST:-} != true ]]; then
    for path in /var/lib/bms "$APP_DIR"; do
        [[ -d $path ]] || continue
        free=$(df -P -B1 -- "$path" | awk 'NR == 2 { print $4 }')
        # Pre-update dump (up to the databases' size), build tree and caches (about 4 GiB), 2 GiB margin.
        needed=$(( ${total_bytes:-0} + 6 * 1024 * 1024 * 1024 ))
        if (( free >= needed )); then report PASS "espacio en $path" "$((free / 1048576)) MiB libres (hacen falta unos $((needed / 1048576)))"
        else report FAIL "espacio en $path" "$((free / 1048576)) MiB libres; hacen falta unos $((needed / 1048576)) (copia de las bases, compilacion, margen)"; fi
    done

    # --- Nginx site -------------------------------------------------------------------------------------------
    if [[ -f $NGINX_SITE ]] && declare -F bms_nginx_unknown_lines >/dev/null; then
        names=$(bms_nginx_site_names "$NGINX_SITE")
        count=$(wc -w <<< "$names")
        if (( count > 1 )); then report INFO 'sitio Nginx nombres' "$count nombres ($names): se conservan todos"
        else report PASS 'sitio Nginx nombres' "$names"; fi
        unknown=$(bms_nginx_unknown_lines "$NGINX_SITE")
        if [[ -n $unknown ]]; then
            report WARN 'sitio Nginx cambios locales' "$(wc -l <<< "$unknown") lineas que ningun instalador escribio; update.sh NO lo reemplazara sin BMS_NGINX_REPLACE_SITE=yes"
            head -n 20 <<< "$unknown" | while IFS= read -r line; do printf '        %s\n' "$line"; done
        else
            report PASS 'sitio Nginx cambios locales' 'ninguno: update.sh puede regenerarlo'
        fi
    elif [[ -f $NGINX_SITE ]]; then
        report WARN 'sitio Nginx' 'no se pudo revisar (falta nginx-site.sh)'
    else
        report FAIL 'sitio Nginx' "no existe $NGINX_SITE"
    fi
    if [[ -e /etc/nginx/sites-enabled/default ]]; then report WARN 'sitio default de Nginx' 'habilitado: los nombres desconocidos siguen recibiendolo en el puerto 80'
    else report PASS 'sitio default de Nginx' 'no habilitado'; fi

    # --- Files ----------------------------------------------------------------------------------------------
    owner_mode=$(stat -c '%U:%G %a' -- "$APP_DIR" 2>/dev/null)
    if [[ $owner_mode == 'root:root 755' ]]; then report PASS "$APP_DIR" "$owner_mode"
    else report WARN "$APP_DIR" "${owner_mode:-no existe} (update.sh lo deja root:root 755)"; fi
    if [[ -d $APP_DIR/source/.git ]]; then
        dirty=$(runuser -u bms -- env GIT_OPTIONAL_LOCKS=0 git -C "$APP_DIR/source" status --porcelain --untracked-files=normal 2>/dev/null | wc -l)
        if [[ $dirty == 0 ]]; then report PASS 'arbol /opt/bms/source' 'sin cambios locales'
        else report WARN 'arbol /opt/bms/source' "$dirty archivos cambiados o nuevos (ya no se usa para migrar; solo referencia)"; fi
    else
        report FAIL 'arbol /opt/bms/source' 'no es un repositorio git (update.sh lo exige)'
    fi
    for file in /usr/local/lib/bms/allowed_signers /usr/local/lib/bms/web_dist_public.pem; do
        if [[ -e $file ]]; then report INFO "$file" 'instalada: se exigira firma'
        else report INFO "$file" 'no instalada: sin firma (SIGNING.md)'; fi
    done
    if [[ -e /etc/systemd/system/bms.service.d/override.conf ]]; then
        report WARN 'override.conf de bms.service' 'existe (systemctl edit): revisalo, puede anular el endurecimiento'
    else
        report PASS 'override.conf de bms.service' 'no existe'
    fi
    if [[ -f /var/lib/bms/deployed-commit ]]; then report PASS 'commit desplegado' "$(head -c 40 /var/lib/bms/deployed-commit)"
    else report INFO 'commit desplegado' 'no registrado: la primera actualizacion por SSH lo escribe; hasta entonces la app no puede actualizar'; fi
fi

# --- Rehearsal of the PostgreSQL work of update.sh -----------------------------------------------------------------
# update.sh --rehearse runs the role move, the grant blocks and the migrations of the real update in transactions that
# it rolls back, and proves with a before/after snapshot that it changed nothing (rehearse.sh). Its lines are shown as
# they are (PASS / WARN / INFO / FAIL "ensayo ..."); a FAIL there means the update would fail the same way.
UPDATE_SH=${BMS_PREFLIGHT_UPDATE_SH:-$SELF_DIR/update.sh}
if [[ ${BMS_PREFLIGHT_NO_REHEARSE:-} == true ]]; then
    report WARN 'ensayo de update.sh' 'omitido (BMS_PREFLIGHT_NO_REHEARSE=true): nada prueba que la segunda fase funcione'
elif [[ ! -f $UPDATE_SH ]]; then
    report FAIL 'ensayo de update.sh' "falta $UPDATE_SH (update.sh junto a preflight.sh): el ensayo no se pudo hacer"
else
    REHEARSE_REF=${REHEARSE_REF:-$(config_value CORE_UPDATE_REF)}
    REHEARSE_REF=${REHEARSE_REF:-master}
    rehearse_status=0
    bash "$UPDATE_SH" --rehearse --ref "$REHEARSE_REF" > "$WORK/rehearse.out" 2> "$WORK/rehearse.err" || rehearse_status=$?
    rehearse_fails=0
    while IFS= read -r line; do
        case $line in
            FAIL*) printf '%s\n' "$line"; FAILS=$((FAILS + 1)); rehearse_fails=$((rehearse_fails + 1)) ;;
            WARN*) printf '%s\n' "$line"; WARNS=$((WARNS + 1)) ;;
            PASS*|INFO*|'        '*) printf '%s\n' "$line" ;;
        esac
    done < "$WORK/rehearse.out"
    if (( rehearse_status == 2 && rehearse_fails == 0 )); then
        report FAIL 'ensayo de update.sh' 'incompleto: alguna base no se ensayo por bloqueo; no vale como prueba. Repite el ensayo en un momento tranquilo'
    elif (( rehearse_status != 0 && rehearse_fails == 0 )); then
        report FAIL 'ensayo de update.sh' "termino con codigo $rehearse_status sin ejecutar el ensayo: $(tail -n 3 "$WORK/rehearse.err" | paste -sd ' ' -)"
    elif (( rehearse_status == 0 )); then
        report PASS 'ensayo de update.sh' "update.sh --rehearse --ref $REHEARSE_REF termino bien: la segunda fase (roles, permisos, migraciones) se ejecuto completa y se revirtio"
    fi
fi

printf '\n%s FAIL, %s WARN\n' "$FAILS" "$WARNS"
(( FAILS == 0 ))
