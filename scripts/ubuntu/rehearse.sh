#!/usr/bin/env bash
# The rehearsal of update.sh's PostgreSQL work (SELF_HOSTING.md section 15, "update.sh --rehearse"), sourced after
# db-roles.sh by update.sh. It proves, on the real cluster and while the service runs, that the second phase will work,
# and leaves NOTHING behind.
#
# What it does, for the control database and for every company database that update.sh would migrate: ONE psql
# session, ONE transaction, ended with ROLLBACK, in which it runs the same SQL text the real run runs (db-roles.sh's
# bms_sql_* functions and whatever the caller's hooks print for the migrations and grant blocks):
#   1. the provisioning role created or adopted, its SET memberships and CONNECT grants;
#   2. the role layout move of that database (grants, owners of the objects, activity log, data keys);
#   3. the runtime role's membership in the server owner roles revoked, its attributes set, the layout checked;
#   4. the runtime role and the provisioning login (SET SESSION AUTHORIZATION, then SET ROLE <owner>) proven able to
#      work in that database;
#   5. the migrations and grant blocks that would be applied, as the owner through the provisioning login (company
#      databases) or as the superuser (control), then the access checks again over the result;
#   6. a snapshot of roles, memberships, owners and ACLs before and after: the difference is what update.sh would change.
# Roles are cluster-wide, but CREATE ROLE, ALTER ROLE, GRANT, REVOKE and ALTER ... OWNER are transactional: each database
# repeats step 1 inside its own transaction (a role created in one is gone after its ROLLBACK, so the next one creates it
# again). Nothing needs a throwaway role or a trap.
# Before the first transaction and after the last one the whole cluster is snapshotted (roles with a hash of their
# verifier, memberships with their options, database owners and ACLs; in every connectable database the owners and ACLs
# of relations, columns, routines, types, schemas and the default ACLs). If the two snapshots differ the rehearsal FAILs
# loudly and prints the difference.
#
# The service keeps running: lock_timeout is short (BMS_REHEARSE_LOCK_TIMEOUT, 500ms) and a database whose locks cannot
# be taken is retried and then reported WARN "no ensayado por bloqueo", never waited for. Limits are in
# SELF_HOSTING.md: ALTER ... OWNER takes ACCESS EXCLUSIVE locks (held until the ROLLBACK, normally well under a second,
# and only on objects that would change owner); passwords, pg_hba, the real TCP login, network-visible behaviour of the
# new release and anything the server's startup does are not rehearsed.
#
# The caller defines (besides bms_pg_transport, db-roles.sh):
#   bms_rehearse_steps_sql <control|tenant> <database>   prints the psql script of the migrations and grant blocks
#                                                         update.sh would run there (update.sh builds it from its own
#                                                         functions). Optional: without it only the layout is rehearsed.
# Output: one line per check as preflight.sh prints them (PASS, WARN, INFO, FAIL, then " name — reason"); the return
# status is 1 when any line is FAIL.

# [[ =~ ]] with LC_ALL=C for this one test, whatever the caller's locale: [a-z], [0-9a-f] or [!-~] mean ASCII.
bms_ascii_match() { local LC_ALL=C; [[ $1 =~ $2 ]]; }

BMS_REHEARSE_FAILS=0
BMS_REHEARSE_WARNS=0

bms_rehearse_say() {
    local status=$1 name=$2 reason=${3:-}
    printf '%-4s %s%s\n' "$status" "$name" "${reason:+ — $reason}"
    [[ $status != FAIL ]] || BMS_REHEARSE_FAILS=$((BMS_REHEARSE_FAILS + 1))
    [[ $status != WARN ]] || BMS_REHEARSE_WARNS=$((BMS_REHEARSE_WARNS + 1))
    return 0
}

# A DO block that fails with a fixed message (a psql variable cannot be used inside a dollar-quoted string).
bms_rehearse_raise() { printf "DO \$do\$ BEGIN RAISE EXCEPTION '%s'; END \$do\$;\n" "$1"; }

# psql script: the provisioning login (session authorization, then SET ROLE to the owner) can work in [db]; the runtime
# role's grants on the activity log are what ACTIVITY_LOG.md says. Variables: runtime, provisioner, owner, db, tenant.
bms_rehearse_sql_access() {
    cat <<'SQL'
SELECT pg_catalog.has_database_privilege(:'provisioner', :'db', 'CONNECT') AS prov_connect \gset
\if :prov_connect
\else
SQL
    bms_rehearse_raise 'el login de aprovisionamiento no puede conectarse a la base'
    cat <<'SQL'
\endif
\if :tenant
SET LOCAL SESSION AUTHORIZATION :"provisioner";
SET ROLE :"owner";
SELECT current_user = :'owner' AND session_user = :'provisioner' AS prov_acts \gset
RESET ROLE;
RESET SESSION AUTHORIZATION;
\if :prov_acts
\else
SQL
    bms_rehearse_raise 'el login de aprovisionamiento no puede actuar como el dueno'
    cat <<'SQL'
\endif
SELECT CASE WHEN pg_catalog.to_regclass('public.activity_log') IS NULL THEN true ELSE
       pg_catalog.has_table_privilege(:'runtime', 'public.activity_log', 'SELECT')
       AND pg_catalog.has_table_privilege(:'runtime', 'public.activity_log', 'INSERT')
       AND NOT pg_catalog.has_table_privilege(:'runtime', 'public.activity_log', 'UPDATE')
       AND NOT pg_catalog.has_table_privilege(:'runtime', 'public.activity_log', 'DELETE')
       AND NOT pg_catalog.has_table_privilege(:'runtime', 'public.activity_log', 'TRUNCATE')
       AND pg_catalog.has_table_privilege(:'runtime', 'public.activity_log_chain', 'SELECT')
       AND pg_catalog.has_table_privilege(:'runtime', 'public.activity_log_chain', 'INSERT')
       AND NOT pg_catalog.has_table_privilege(:'runtime', 'public.activity_log_chain', 'UPDATE')
       AND NOT pg_catalog.has_table_privilege(:'runtime', 'public.activity_log_chain', 'DELETE')
       AND pg_catalog.has_sequence_privilege(:'runtime', 'public.activity_log_id_seq', 'USAGE') END AS log_grants \gset
\if :log_grants
\else
SQL
    bms_rehearse_raise 'el rol del runtime no tiene exactamente SELECT, INSERT sobre el registro de actividad'
    cat <<'SQL'
\endif
SELECT CASE WHEN pg_catalog.to_regclass('public.tenant_data_keys') IS NULL THEN true ELSE
       pg_catalog.has_table_privilege(:'runtime', 'public.tenant_data_keys', 'SELECT')
       AND pg_catalog.has_table_privilege(:'runtime', 'public.tenant_data_keys', 'INSERT')
       AND NOT pg_catalog.has_table_privilege(:'runtime', 'public.tenant_data_keys', 'UPDATE')
       AND NOT pg_catalog.has_table_privilege(:'runtime', 'public.tenant_data_keys', 'DELETE') END AS key_grants \gset
\if :key_grants
\else
SQL
    bms_rehearse_raise 'el rol del runtime no tiene exactamente SELECT, INSERT sobre la llave de datos'
    cat <<'SQL'
\endif
\endif
SQL
}

# The whole psql script of one database. [steps]: the migrations and grant blocks (psql script text, may be empty).
bms_rehearse_script() {
    local kind=$1 database=$2 owner=$3 runtime=$4 provisioner=$5 owners=$6 control_db=$7 steps=$8 role tenant=0
    local lock_timeout=${BMS_REHEARSE_LOCK_TIMEOUT:-500ms} statement_timeout=${BMS_REHEARSE_STATEMENT_TIMEOUT:-120s}
    { bms_ascii_match "$lock_timeout" '^[0-9]{1,6}(ms|s)$' && bms_ascii_match "$statement_timeout" '^[0-9]{1,6}(ms|s)$'; } || {
        printf 'ERROR: BMS_REHEARSE_LOCK_TIMEOUT o BMS_REHEARSE_STATEMENT_TIMEOUT no validos (por ejemplo 500ms, 120s).\n' >&2
        return 1
    }
    [[ $kind == tenant ]] && tenant=1
    { bms_valid_role "$runtime" && bms_valid_role "$provisioner" && bms_valid_control_db "$control_db"; } || { printf 'ERROR: rol o base no valido.\n' >&2; return 1; }
    if [[ $kind == tenant ]]; then
        { bms_valid_tenant_db "$database" && bms_valid_role "$owner"; } || { printf 'ERROR: base o dueno no validos: %q %q.\n' "$database" "$owner" >&2; return 1; }
    else
        [[ $database == "$control_db" ]] || { printf 'ERROR: la base central no coincide.\n' >&2; return 1; }
    fi
    owners=$(bms_roles_validated 'roles de server owner' <<< "$owners") || return 1
    bms_psql_set runtime "$runtime" && bms_psql_set provisioner "$provisioner" && bms_psql_set db "$database" &&
        bms_psql_set tenant "$tenant" || return 1
    [[ $kind != tenant ]] || bms_psql_set owner "$owner" || return 1
    printf 'BEGIN;\nSET LOCAL lock_timeout = '"'%s'"';\nSET LOCAL statement_timeout = '"'%s'"';\nSET LOCAL search_path = pg_catalog, pg_temp;\n' "$lock_timeout" "$statement_timeout"
    printf 'SELECT pg_catalog.pg_current_xact_id() AS rehearsal_xid \\gset\n'
    printf '\\echo @@instantanea inicial\n'
    bms_psql_set tag B || return 1
    bms_sql_snapshot_global
    bms_sql_snapshot_local
    printf '\\echo @@rol de aprovisionamiento y pertenencias\n'
    bms_sql_provisioner_role create
    while IFS= read -r role; do
        [[ -n $role ]] || continue
        bms_psql_set role "$role" && bms_psql_set member "$provisioner" || return 1
        bms_sql_grant_set_only
    done <<< "$owners"
    bms_psql_set role "$runtime" && bms_psql_set member "$provisioner" || return 1
    bms_sql_grant_set_only
    bms_psql_set member "$provisioner" || return 1
    bms_sql_connect_grant
    if [[ $kind == tenant ]]; then
        printf '\\echo @@organizacion de roles de la empresa\n'
        bms_sql_tenant_layout
    else
        printf '\\echo @@organizacion de roles de la base central\n'
        bms_sql_control_layout
    fi
    printf '\\echo @@el runtime deja de ser miembro de los duenos\n'
    while IFS= read -r role; do
        [[ -n $role ]] || continue
        bms_psql_set role "$role" && bms_psql_set member "$runtime" || return 1
        bms_sql_revoke_membership
    done <<< "$owners"
    bms_sql_runtime_attributes
    printf '\\echo @@comprobacion de la organizacion\n'
    printf 'SELECT count(*) = 0 AS layout_ok FROM (\n'
    bms_sql_layout_problems
    printf '\n) problems \\gset\n\\if :layout_ok\n\\else\n'
    bms_sql_layout_problems
    printf ';\n'
    bms_rehearse_raise 'organizacion de roles incompleta'
    printf '\\endif\n'
    printf '\\echo @@acceso del runtime y del login de aprovisionamiento\n'
    bms_sql_verify_runtime
    bms_rehearse_sql_access
    if [[ -n $steps ]]; then
        printf '\\echo @@migraciones y permisos que se aplicarian\n'
        printf 'SET LOCAL search_path = public;\n'
        if [[ $kind == tenant ]]; then
            printf 'SET LOCAL SESSION AUTHORIZATION :"provisioner";\nSET ROLE :"owner";\n'
        fi
        printf '%s\n' "$steps"
        if [[ $kind == tenant ]]; then
            printf 'RESET ROLE;\nRESET SESSION AUTHORIZATION;\n'
        fi
        printf 'SET LOCAL search_path = pg_catalog, pg_temp;\n'
        printf '\\echo @@acceso tras las migraciones\n'
        bms_sql_verify_runtime
        bms_rehearse_sql_access
    fi
    printf '\\echo @@instantanea final\n'
    bms_psql_set tag A || return 1
    bms_sql_snapshot_global
    bms_sql_snapshot_local
    printf 'SELECT pg_catalog.pg_current_xact_id() = :'"'"'rehearsal_xid'"'"'::pg_catalog.xid8 AS still_open \\gset\n\\if :still_open\n\\else\n'
    bms_rehearse_raise 'el SQL del ensayo termino la transaccion'
    printf '\\endif\nROLLBACK;\n'
}

# Human summary of the difference between two snapshots (files of "kind|key|value" rows, sorted): "TOTAL ..." first, then
# one line per fact that changes: "~" changed, "-" removed, "+" added. [cluster] 0 leaves out roles and memberships
# (already shown with the first database); objects, columns and routines a migration creates are only counted unless
# [all] is 1.
bms_rehearse_diff() {
    LC_ALL=C awk -F'|' -v cluster="${3:-1}" -v all="${4:-0}" '
    function label(kind) {
        return kind == "role" ? "rol" : kind == "member" ? "pertenencia" : kind == "database" ? "base" : kind == "relation" ? "objeto" : \
               kind == "column" ? "columna" : kind == "routine" ? "funcion" : kind == "type" ? "tipo" : kind == "schema" ? "esquema" : "permisos por defecto"
    }
    function decode(v,    f, n, text) {
        n = split(v, f, ",")
        text = (f[5] == "t" ? "LOGIN" : "NOLOGIN") " " (f[2] == "t" ? "INHERIT" : "NOINHERIT")
        if (f[1] == "t") text = text " SUPERUSER"
        if (f[4] == "t") text = text " CREATEDB"
        if (f[3] == "t") text = text " CREATEROLE"
        if (f[6] == "t") text = text " REPLICATION"
        if (f[7] == "t") text = text " BYPASSRLS"
        return text (f[11] != "" ? " con contrasena" : " sin contrasena")
    }
    function show(kind, v) { return kind == "role" ? decode(v) : v }
    FNR == NR { before[$1 "|" $2] = substr($0, length($1) + length($2) + 3); keys[$1 "|" $2] = 1; next }
    { after[$1 "|" $2] = substr($0, length($1) + length($2) + 3); keys[$1 "|" $2] = 1 }
    END {
        for (key in keys) {
            split(key, parts, "|")
            kind = parts[1]; name = substr(key, length(kind) + 2)
            if (!(key in before) && (key in after)) { sign = "+"; added++ }
            else if ((key in before) && !(key in after)) { sign = "-"; removed++ }
            else if (before[key] != after[key]) { sign = "~"; changed++ }
            else continue
            if (!cluster && (kind == "role" || kind == "member")) continue
            if (sign == "+" && kind != "role" && kind != "member" && kind != "database") { created++; if (!all) continue }
            if (sign == "+") line = "+ " label(kind) " " name ": " show(kind, after[key])
            else if (sign == "-") line = "- " label(kind) " " name ": " show(kind, before[key])
            else line = "~ " label(kind) " " name ": " show(kind, before[key]) " -> " show(kind, after[key])
            order = (kind == "role" ? 1 : kind == "member" ? 2 : kind == "database" ? 3 : sign == "~" ? 4 : sign == "-" ? 5 : 6)
            print order "|" line
        }
        print "0|TOTAL " added + 0 " nuevos (" created + 0 " objetos, columnas o funciones creados por las migraciones), " removed + 0 " quitados, " changed + 0 " cambiados"
    }' "$1" "$2" | LC_ALL=C sort -t'|' -k1,1n -k2 | LC_ALL=C sed 's/^[0-9]*|//'
}

# One database: run its script, retry on lock timeouts, report. Arguments as bms_rehearse_script plus the work directory.
bms_rehearse_database() {
    local kind=$1 database=$2 owner=$3 runtime=$4 provisioner=$5 owners=$6 control_db=$7 work=$8
    local steps='' script attempt=0 attempts=${BMS_REHEARSE_ATTEMPTS:-3} status step error label line shown=0 limit=${BMS_REHEARSE_DETAIL:-40}
    label="ensayo $kind $database"
    if declare -F bms_rehearse_steps_sql >/dev/null; then
        steps=$(bms_rehearse_steps_sql "$kind" "$database" 2> "$work/steps.err") || {
            bms_rehearse_say FAIL "$label" "no se pudo preparar el SQL de las migraciones: $(head -n 3 "$work/steps.err" | paste -sd ' ' -)"
            return 1
        }
    fi
    # Statements that end the transaction would make the rollback meaningless; plain "END;" is also the end of a
    # plpgsql block, so the transaction id is checked at the end of the script as well, and the snapshots at the end of
    # the run.
    if LC_ALL=C grep -Eiq '^(COMMIT|ROLLBACK|ABORT|BEGIN|SAVEPOINT|RELEASE|PREPARE TRANSACTION|START TRANSACTION)([^;]*);[[:space:]]*$' <<< "$steps"; then
        bms_rehearse_say FAIL "$label" 'el SQL de las migraciones contiene control de transaccion (COMMIT, ROLLBACK, BEGIN...); no se ejecuta'
        return 1
    fi
    script=$(bms_rehearse_script "$kind" "$database" "$owner" "$runtime" "$provisioner" "$owners" "$control_db" "$steps" 2> "$work/script.err") || {
        bms_rehearse_say FAIL "$label" "no se pudo preparar el ensayo: $(head -n 3 "$work/script.err" | paste -sd ' ' -)"
        return 1
    }
    printf '%s\n' "$script" > "$work/script.sql"
    while :; do
        attempt=$((attempt + 1))
        status=0
        bms_pg_query "$database" -v VERBOSITY=verbose < "$work/script.sql" > "$work/out" 2> "$work/err" || status=$?
        (( status != 0 )) || break
        if LC_ALL=C grep -Eq 'ERROR:  (55P03|57014)' "$work/err" && (( attempt < attempts )); then
            sleep "${BMS_REHEARSE_RETRY_SLEEP:-2}"
            continue
        fi
        # (|| true: with set -e and pipefail a grep that finds nothing must not end the run)
        step=$(LC_ALL=C grep '^@@' "$work/out" | tail -n 1) || true
        step=${step#@@}
        error=$(LC_ALL=C grep -m 1 'ERROR:' "$work/err" | LC_ALL=C sed 's/^.*ERROR:  *//') || true
        [[ -n $error ]] || error=$(head -n 2 "$work/err" | paste -sd ' ' -)
        if LC_ALL=C grep -Eq 'ERROR:  (55P03|57014)' "$work/err"; then
            bms_rehearse_say WARN "$label" "no ensayado por bloqueo: $attempts intentos sin obtener los bloqueos en ${BMS_REHEARSE_LOCK_TIMEOUT:-500ms} (en: ${step:-inicio}); el servicio esta usando esas tablas, repite el ensayo en un momento tranquilo"
        else
            bms_rehearse_say FAIL "$label" "fallo en '${step:-inicio}': $error"
            { LC_ALL=C grep -h -E '^(DETAIL|HINT|CONTEXT|STATEMENT):' "$work/err" | head -n 4 | LC_ALL=C sed 's/^/        /'; } || true
            # Rows a failing check printed before the error (the layout problems, for one).
            { LC_ALL=C grep -v -E '^(@@|B\||A\||Migration already applied)' "$work/out" | head -n 5 | LC_ALL=C sed 's/^/        /'; } || true
        fi
        return 1
    done
    LC_ALL=C sed -n 's/^B|//p' "$work/out" | LC_ALL=C sort > "$work/before"
    LC_ALL=C sed -n 's/^A|//p' "$work/out" | LC_ALL=C sort > "$work/after"
    if [[ ! -s $work/before || ! -s $work/after ]]; then
        bms_rehearse_say FAIL "$label" 'las instantaneas del ensayo salieron vacias'
        return 1
    fi
    bms_rehearse_diff "$work/before" "$work/after" "$([[ -f $work/cluster-shown ]] && echo 0 || echo 1)" "${BMS_REHEARSE_ALL_NEW:-0}" > "$work/diff"
    : > "$work/cluster-shown"
    bms_rehearse_say PASS "$label" "ejecutado y revertido (ROLLBACK); $(head -n 1 "$work/diff" | LC_ALL=C sed 's/^TOTAL //')"
    while IFS= read -r line; do
        [[ $line != TOTAL* ]] || continue
        if (( shown < limit )); then printf 'INFO   %s\n' "$line"; fi
        shown=$((shown + 1))
    done < "$work/diff"
    (( shown <= limit )) || printf 'INFO   ... y %s cambios mas (BMS_REHEARSE_DETAIL=N para ver mas)\n' "$((shown - limit))"
    return 0
}

# Snapshot of the whole cluster into [file] (sorted rows "database|kind|key|value"); returns 1 when it cannot be read.
bms_rehearse_snapshot() {
    local file=$1 work=$2 databases database script rows
    : > "$work/snapshot.raw"
    script=$({ bms_psql_set tag S && bms_sql_snapshot_global; }) || return 1
    rows=$(bms_pg_query postgres <<< "$script") || return 1
    # shellcheck disable=SC2001 # per-line anchored
    LC_ALL=C sed 's/^S|/*|/' <<< "$rows" >> "$work/snapshot.raw"
    databases=$(bms_pg_query postgres -c 'SELECT datname FROM pg_catalog.pg_database WHERE NOT datistemplate AND datallowconn ORDER BY 1') || return 1
    script=$({ bms_psql_set tag S && bms_sql_snapshot_local; }) || return 1
    while IFS= read -r database; do
        [[ -n $database ]] || continue
        if ! bms_ascii_match "$database" '^[A-Za-z0-9_.-]{1,63}$'; then
            bms_rehearse_say WARN 'instantanea' "la base $(printf '%q' "$database") tiene un nombre raro y no se incluye"
            continue
        fi
        rows=$(bms_pg_query "$database" <<< "$script") || { printf 'ERROR: no se pudo leer la base %s para la instantanea.\n' "$database" >&2; return 1; }
        # shellcheck disable=SC2001 # per-line anchored
        [[ -z $rows ]] || LC_ALL=C sed "s/^S|/$database|/" <<< "$rows" >> "$work/snapshot.raw"
    done <<< "$databases"
    LC_ALL=C sort "$work/snapshot.raw" > "$file"
}

# The rehearsal. [control_db] [runtime] [provisioner]: as update.sh would use them. Uses [work] for its files.
bms_rehearse_run_in() {
    local control_db=$1 runtime=$2 provisioner=$3 work=$4 owners owners_text classified tenants kind name rest
    local assessment layout pending done_count attempted attempts_seen=0 touched=0 mixed=0 database owner n
    BMS_REHEARSE_FAILS=0
    BMS_REHEARSE_WARNS=0
    if ! { bms_valid_control_db "$control_db" && bms_valid_role "$runtime" && bms_valid_role "$provisioner"; }; then
        bms_rehearse_say FAIL 'ensayo' 'base central, rol del runtime o rol de aprovisionamiento no validos'
        return 1
    fi
    if [[ $runtime == "$provisioner" ]]; then
        bms_rehearse_say FAIL 'ensayo' 'el rol de aprovisionamiento debe ser otro que el del runtime'
        return 1
    fi
    if ! owners=$(bms_server_owner_roles "$control_db" 2> "$work/err"); then
        bms_rehearse_say FAIL 'roles de server owner' "no se pudieron leer o no son validos: $(head -n 2 "$work/err" | paste -sd ' ' -)"
        return 1
    fi
    # Whatever the function returned is checked here, at the point of use: a stray token is never a role.
    if ! owners=$(bms_roles_validated 'roles de server owner' <<< "$owners" 2> "$work/err"); then
        bms_rehearse_say FAIL 'roles de server owner' "valor que no es un rol: $(head -n 2 "$work/err" | paste -sd ' ' -)"
        return 1
    fi
    if [[ -z $owners ]]; then
        bms_rehearse_say FAIL 'roles de server owner' "no hay ninguno en $control_db"
        return 1
    fi
    owners_text=$(paste -sd ' ' - <<< "$owners")
    bms_rehearse_say PASS 'roles de server owner' "$owners_text (validos)"
    n=$(bms_pg_count postgres -v name="$runtime" <<'SQL'
SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname = :'name';
SQL
) || { bms_rehearse_say FAIL "rol $runtime" 'no se pudo consultar'; return 1; }
    if [[ $n == 0 ]]; then bms_rehearse_say FAIL "rol $runtime" 'no existe'; return 1; fi
    assessment=$(bms_provisioner_assess "$runtime" "$provisioner" "$owners" 2> "$work/err") || {
        bms_rehearse_say FAIL "rol $provisioner" "no se pudo revisar: $(head -n 2 "$work/err" | paste -sd ' ' -)"
        return 1
    }
    case $assessment in
        absent) bms_rehearse_say INFO "rol $provisioner" 'no existe: update.sh lo crea (el ensayo lo crea dentro de cada transaccion)' ;;
        'adoptable clean') bms_rehearse_say INFO "rol $provisioner" 'existe, sin pertenencias ni privilegios: update.sh lo adopta con una contrasena nueva' ;;
        'adoptable touched')
            touched=1
            bms_rehearse_say INFO "rol $provisioner" 'existe con lo que un intento anterior de update.sh le da (pertenencias, CONNECT): update.sh lo adopta con una contrasena nueva' ;;
        *) bms_rehearse_say FAIL "rol $provisioner" "existe y ${assessment#unexpected: }; update.sh se detendria sin cambiar nada" ;;
    esac
    classified=$(bms_classify_tenant_databases "$control_db" 2> "$work/err") || {
        bms_rehearse_say FAIL 'empresas' "no se pudo leer companies: $(head -n 2 "$work/err" | paste -sd ' ' -)"
        return 1
    }
    while read -r kind name rest; do
        case $kind in
            reject) bms_rehearse_say FAIL "empresa $name" "$rest: update.sh no cambiaria ningun rol" ;;
            missing) bms_rehearse_say WARN "empresa $name" 'registrada en companies pero la base no existe (se omite)' ;;
        esac
    done <<< "$classified"
    tenants=$(LC_ALL=C awk '$1 == "ok" { print $2, $3 }' <<< "$classified")
    if layout=$(bms_layout_status "$runtime" "$provisioner" "$tenants" 2> "$work/err"); then
        while read -r database owner pending done_count attempted; do
            [[ -n $database ]] || continue
            (( attempted == 0 )) || attempts_seen=$((attempts_seen + 1))
            if (( pending > 0 && done_count > 0 )); then
                mixed=$((mixed + 1))
                bms_rehearse_say INFO "empresa $database" "a medio mover: $pending objetos siguen de postgres o del runtime y $done_count relaciones ya son de $owner (un intento anterior o tablas creadas por el servidor); update.sh termina el trabajo"
            elif (( pending > 0 )); then
                bms_rehearse_say INFO "empresa $database" "organizacion anterior: $pending objetos por pasar a $owner"
            else
                bms_rehearse_say INFO "empresa $database" "sin objetos pendientes ($done_count relaciones de $owner)"
            fi
        done <<< "$layout"
        if (( attempts_seen > 0 || touched == 1 || mixed > 0 )); then
            bms_rehearse_say WARN 'estado parcial' "huellas de un intento anterior: $attempts_seen base(s) con CONNECT de $provisioner, $mixed a medio mover, rol de aprovisionamiento $( (( touched == 1 )) && echo 'con pertenencias' || echo 'sin pertenencias'); update.sh es idempotente y continua donde quedo"
        fi
    else
        bms_rehearse_say WARN 'estado de la organizacion' "no se pudo leer: $(head -n 2 "$work/err" | paste -sd ' ' -)"
    fi
    if (( BMS_REHEARSE_FAILS > 0 )); then
        bms_rehearse_say INFO 'ensayo en transacciones' 'omitido: corrige lo anterior (update.sh se detendria igual)'
        return 1
    fi

    if ! bms_rehearse_snapshot "$work/cluster-before" "$work"; then
        bms_rehearse_say FAIL 'instantanea previa' 'no se pudo leer el estado del cluster'
        return 1
    fi
    bms_rehearse_database control "$control_db" '' "$runtime" "$provisioner" "$owners" "$control_db" "$work" || true
    while read -r database owner; do
        [[ -n $database ]] || continue
        bms_rehearse_database tenant "$database" "$owner" "$runtime" "$provisioner" "$owners" "$control_db" "$work" || true
    done <<< "$tenants"

    if ! bms_rehearse_snapshot "$work/cluster-after" "$work"; then
        bms_rehearse_say FAIL 'instantanea posterior' 'no se pudo leer el estado del cluster'
        return 1
    fi
    if cmp -s "$work/cluster-before" "$work/cluster-after"; then
        bms_rehearse_say PASS 'ensayo sin cambios' "instantaneas del cluster antes y despues identicas ($(wc -l < "$work/cluster-before") hechos: roles, pertenencias, bases, duenos y ACL de objetos, columnas, funciones, tipos, esquemas y permisos por defecto)"
    else
        bms_rehearse_say FAIL 'ensayo sin cambios' 'EL ENSAYO DEJO CAMBIOS (o alguien cambio permisos mientras corria); revisa la diferencia:'
        LC_ALL=C diff "$work/cluster-before" "$work/cluster-after" | head -n 40 | LC_ALL=C sed 's/^/        /'
    fi
    (( BMS_REHEARSE_FAILS == 0 ))
}

bms_rehearse_run() {
    local work status=0
    work=$(mktemp -d) || return 1
    bms_rehearse_run_in "$1" "$2" "$3" "$work" || status=$?
    rm -rf --one-file-system -- "$work"
    return "$status"
}
