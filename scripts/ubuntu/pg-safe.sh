#!/usr/bin/env bash
# Sourced by every root script that talks to PostgreSQL (activity-harden.sh, update.sh, install.sh,
# migrations.sh, core-update.sh, domains-sync.sh). The server owner's PostgreSQL role (CREATEDB
# CREATEROLE, the runtime role is a member of it) owns every company database and its schema public:
# it can set per-database defaults (ALTER DATABASE ... SET role / search_path ...) and create
# functions, operators, triggers, rules or views there. A superuser session that obeys those settings,
# resolves a name through public or touches one of that role's tables runs that role's code as the
# superuser. So, here:
#   - every superuser psql call goes through pg_super (or pg_control for the control database):
#     -X, ON_ERROR_STOP, PGOPTIONS that override any per-database setting (role=none, search_path
#     pinned, read-write...), and SET ROLE NONE + SET search_path first;
#   - pg_db_guard runs before a database is touched: per-database settings outside a small allow-list
#     are logged, reset as the superuser (from the maintenance database) and reported; nothing runs in
#     a database while such a setting is still there;
#   - company databases are migrated as the server owner's role, through the runtime login (pg_owner),
#     never as the superuser: whatever that role planted there then runs with its own rights only.
# Tests may point at another server with BMS_PG_HOST / BMS_PG_PORT (root's environment only).

# Settings a per-database default may keep (they cannot steer what a session executes).
readonly PG_SAFE_ALLOWED_SETTINGS=' timezone datestyle intervalstyle lc_messages lc_monetary lc_numeric lc_time default_text_search_config extra_float_digits application_name client_encoding '
readonly PG_SAFE_NAME_PATTERN='^[a-z][a-z0-9_]{0,62}$'
# Findings of pg_db_guard (one line each), for the caller's log and server audit.
PG_GUARD_FINDINGS=()

# Whether a psql argument gives the SQL itself (-c/-f, alone or ending a cluster such as -Atc).
pg_safe_gives_sql() {
    [[ $1 =~ ^-[A-Za-z]*[cf]$ || $1 =~ ^-[cf]. || $1 == --command* || $1 == --file* ]]
}

pg_safe_log() {
    if declare -F log >/dev/null; then log "$*"; else printf '%s\n' "$*" >&2; fi
}

# PGOPTIONS for a privileged session: they win over ALTER DATABASE/ROLE ... SET defaults.
pg_safe_options() {
    local path=$1 role=${2:-none}
    printf '%s' "-c role=$role -c search_path=$path -c default_transaction_read_only=off" \
        " -c default_transaction_isolation=read\\ committed -c transform_null_equals=off" \
        " -c standard_conforming_strings=on -c check_function_bodies=on -c local_preload_libraries=" \
        " -c statement_timeout=0 -c idle_in_transaction_session_timeout=0 -c client_min_messages=notice"
}

# psql as the superuser in database $1 with search path $2; the rest are psql arguments. When they
# give no -c/-f, the SQL comes from stdin. SET ROLE NONE and SET search_path always run first.
pg_super_path() {
    local database=$1 path=$2 sql_path=${2//,/, }
    shift 2
    [[ $database =~ $PG_SAFE_NAME_PATTERN ]] || { pg_safe_log "ERROR: nombre de base no valido: $database"; return 2; }
    local arg input=(-f -)
    for arg in "$@"; do
        pg_safe_gives_sql "$arg" && input=()
    done
    runuser -u postgres -- env PGOPTIONS="$(pg_safe_options "$path")" \
        psql -X --no-psqlrc -q -v ON_ERROR_STOP=1 ${BMS_PG_HOST:+-h "$BMS_PG_HOST"} ${BMS_PG_PORT:+-p "$BMS_PG_PORT"} \
        -d "$database" -c 'SET ROLE NONE' -c "SET search_path = $sql_path" "$@" "${input[@]}"
}

# The superuser in a company (or any untrusted) database: catalog only, never public. SQL fed here
# must schema-qualify everything (public.activity_log, pg_catalog.format...).
pg_super() {
    local database=$1
    shift
    pg_db_guard "$database" || return 1
    pg_super_path "$database" pg_catalog,pg_temp "$@"
}

# The superuser in the maintenance database (owned by the superuser; nothing to guard).
pg_admin_db() { pg_super_path postgres pg_catalog,pg_temp "$@"; }

# The superuser in the control database, whose objects all belong to the superuser and whose schema
# public nobody else may create in (install.sh): public may come first there, so its migrations
# (unqualified names) apply. Refuses a control database that does not look like that.
pg_control() {
    local database=$1
    shift
    pg_db_guard "$database" || return 1
    pg_control_trusted "$database" || return 1
    pg_super_path "$database" public,pg_catalog,pg_temp "$@"
}

pg_control_trusted() {
    local database=$1 untrusted
    untrusted=$(pg_super_path "$database" pg_catalog,pg_temp -At <<'SQL'
SELECT pg_catalog.string_agg(problem, '; ') FROM (
    SELECT 'la base pertenece a ' || pg_catalog.pg_get_userbyid(d.datdba) AS problem FROM pg_catalog.pg_database d
    WHERE d.datname = pg_catalog.current_database() AND NOT (SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.oid = d.datdba)
    UNION ALL
    SELECT 'el esquema public pertenece a ' || pg_catalog.pg_get_userbyid(n.nspowner) FROM pg_catalog.pg_namespace n
    WHERE n.nspname = 'public' AND NOT (SELECT r.rolsuper OR r.rolname = 'pg_database_owner' FROM pg_catalog.pg_roles r WHERE r.oid = n.nspowner)
    UNION ALL
    SELECT 'el rol ' || r.rolname || ' puede crear en el esquema public' FROM pg_catalog.pg_roles r
    WHERE NOT r.rolsuper AND r.rolname !~ '^pg_' AND pg_catalog.has_schema_privilege(r.oid, 'public', 'CREATE')
    UNION ALL
    SELECT 'public.' || c.relname || ' pertenece a ' || pg_catalog.pg_get_userbyid(c.relowner) FROM pg_catalog.pg_class c
    WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND NOT (SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.oid = c.relowner)
    UNION ALL
    SELECT 'public.' || p.proname || ' pertenece a ' || pg_catalog.pg_get_userbyid(p.proowner) FROM pg_catalog.pg_proc p
    WHERE p.pronamespace = 'public'::pg_catalog.regnamespace AND NOT (SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.oid = p.proowner)
    UNION ALL
    SELECT 'el operador public.' || o.oprname || ' pertenece a ' || pg_catalog.pg_get_userbyid(o.oprowner) FROM pg_catalog.pg_operator o
    WHERE o.oprnamespace = 'public'::pg_catalog.regnamespace AND NOT (SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.oid = o.oprowner)
) found
SQL
) || return 1
    if [[ -n $untrusted ]]; then
        pg_safe_log "ERROR: la base central $database no es solo del superusuario ($untrusted): no se ejecuta nada en ella como superusuario."
        return 1
    fi
}

# Checks the per-database defaults of $1 (ALTER DATABASE ... SET and ALTER ROLE ... IN DATABASE ... SET)
# from the maintenance database. Any setting outside the allow-list is logged, reset and added to
# PG_GUARD_FINDINGS; fails if one is still there afterwards. A missing database is not an error.
pg_db_guard() {
    local database=$1 rows row role name setting attempt
    [[ $database =~ $PG_SAFE_NAME_PATTERN ]] || { pg_safe_log "ERROR: nombre de base no valido: $database"; return 1; }
    for attempt in 1 2; do
        rows=$(pg_admin_db -At -F $'\t' -v db="$database" <<'SQL'
SELECT CASE WHEN s.setrole = 0 THEN '-' ELSE pg_catalog.pg_get_userbyid(s.setrole)::TEXT END, item
FROM pg_catalog.pg_db_role_setting s, pg_catalog.unnest(s.setconfig) AS item
WHERE s.setdatabase = (SELECT d.oid FROM pg_catalog.pg_database d WHERE d.datname = :'db')
SQL
) || { pg_safe_log "ERROR: no se pudieron leer los ajustes de la base $database."; return 1; }
        local hostile=0
        while IFS=$'\t' read -r role row; do
            [[ -n $row ]] || continue
            name=${row%%=*}
            name=${name,,}
            [[ $PG_SAFE_ALLOWED_SETTINGS == *" $name "* ]] && continue
            hostile=1
            (( attempt == 1 )) || continue
            setting=$row
            [[ $role == - ]] || setting+=" (rol $role)"
            pg_safe_log "ALERTA: la base $database tiene el ajuste \"$setting\", que podria dirigir una sesion privilegiada: se elimina."
            PG_GUARD_FINDINGS+=("Ajuste eliminado de la base $database: $setting")
            if [[ $role == - ]]; then
                pg_admin_db -v db="$database" -v name="$name" <<'SQL' || true
SELECT pg_catalog.format('ALTER DATABASE %I RESET %I', :'db', :'name') AS statement \gset
:statement;
SQL
            else
                pg_admin_db -v db="$database" -v name="$name" -v role="$role" <<'SQL' || true
SELECT pg_catalog.format('ALTER ROLE %I IN DATABASE %I RESET %I', :'role', :'db', :'name') AS statement \gset
:statement;
SQL
            fi
        done <<< "$rows"
        (( hostile )) || return 0
    done
    pg_safe_log "ERROR: la base $database conserva ajustes peligrosos tras intentar quitarlos: no se ejecuta nada en ella."
    return 1
}

# The runtime login (DB_USER/DB_PASSWORD of server.env) switched to role $2 in database $1, for SQL
# that must not run as the superuser (company migrations). The role must be one the runtime is a
# member of and not a superuser: code planted in the database then runs with that role's rights only.
# Needs PG_OWNER_HOST, PG_OWNER_PORT, PG_OWNER_USER and PG_OWNER_PASSFILE (see pg_owner_prepare).
pg_owner() {
    local database=$1 role=$2
    shift 2
    [[ $database =~ $PG_SAFE_NAME_PATTERN && $role =~ $PG_SAFE_NAME_PATTERN ]] || { pg_safe_log "ERROR: base o rol no validos: $database $role"; return 2; }
    [[ -n ${PG_OWNER_PASSFILE:-} ]] || { pg_safe_log 'ERROR: falta pg_owner_prepare.'; return 2; }
    pg_db_guard "$database" || return 1
    local arg input=(-f -)
    for arg in "$@"; do
        pg_safe_gives_sql "$arg" && input=()
    done
    env PGPASSFILE="$PG_OWNER_PASSFILE" PGOPTIONS="$(pg_safe_options public,pg_catalog "$role")" PGCONNECT_TIMEOUT=10 \
        psql -X --no-psqlrc -q -v ON_ERROR_STOP=1 -h "$PG_OWNER_HOST" -p "$PG_OWNER_PORT" -U "$PG_OWNER_USER" -d "$database" \
        -c "SET ROLE \"$role\"" -c 'SET search_path = public, pg_catalog' "$@" "${input[@]}"
}

# Reads the runtime login from server.env ($1) into a root-only passfile; call pg_owner_cleanup after.
pg_owner_prepare() {
    local config=$1 url user password hostport
    url=$(awk -F= '$1 == "DB_URL" { sub(/^[^=]*=/, ""); print; exit }' "$config")
    user=$(awk -F= '$1 == "DB_USER" { sub(/^[^=]*=/, ""); print; exit }' "$config")
    password=$(awk -F= '$1 == "DB_PASSWORD" { sub(/^[^=]*=/, ""); print; exit }' "$config" |
        python3 -c 'import json, sys; text = sys.stdin.read().strip(); print(json.loads(text) if text.startswith("\"") else text, end="")') || return 1
    hostport=${url#jdbc:postgresql://}
    hostport=${hostport%%/*}
    PG_OWNER_HOST=${hostport%%:*}
    PG_OWNER_PORT=5432
    [[ $hostport == *:* ]] && PG_OWNER_PORT=${hostport##*:}
    PG_OWNER_USER=$user
    [[ $PG_OWNER_USER =~ $PG_SAFE_NAME_PATTERN && $PG_OWNER_PORT =~ ^[0-9]+$ && -n $PG_OWNER_HOST && -n $password ]] ||
        { pg_safe_log 'ERROR: DB_URL, DB_USER o DB_PASSWORD no validos en server.env.'; return 1; }
    PG_OWNER_PASSFILE=$(mktemp)
    chmod 600 "$PG_OWNER_PASSFILE"
    # .pgpass format: host:port:database:user:password, with : and \ escaped.
    password=${password//\\/\\\\}
    printf '%s:%s:*:%s:%s\n' "$PG_OWNER_HOST" "$PG_OWNER_PORT" "$PG_OWNER_USER" "${password//:/\\:}" > "$PG_OWNER_PASSFILE"
}

pg_owner_cleanup() {
    [[ -n ${PG_OWNER_PASSFILE:-} ]] && rm -f -- "$PG_OWNER_PASSFILE"
    PG_OWNER_PASSFILE=
}

# The role company migrations run as in database $1: its owner, when that is a non-superuser role the
# runtime role ($2) is a member of (the layout install.sh and the server create). Empty otherwise.
pg_owner_role_of() {
    pg_admin_db -At -v db="$1" -v runtime="$2" <<'SQL'
SELECT r.rolname FROM pg_catalog.pg_database d JOIN pg_catalog.pg_roles r ON r.oid = d.datdba
WHERE d.datname = :'db' AND NOT r.rolsuper AND pg_catalog.pg_has_role(:'runtime', r.oid, 'MEMBER')
SQL
}

# One row of evidence in the server audit (control database $1): action $2, company $3 (may be
# empty), details $4 (a JSON object). Best effort: a failure is logged, never fatal.
pg_audit_evidence() {
    pg_control "$1" -v action="$2" -v company="$3" -v details="$4" <<'SQL' >/dev/null || pg_safe_log "AVISO: no se pudo escribir $2 en provisioning_audit_log."
INSERT INTO public.provisioning_audit_log (id, actor_role, action, company_code, details)
VALUES (pg_catalog.gen_random_uuid(), 'system', :'action', NULLIF(:'company', ''), CAST(:'details' AS pg_catalog.jsonb));
SQL
}
