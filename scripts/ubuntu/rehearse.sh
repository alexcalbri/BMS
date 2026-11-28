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
BMS_REHEARSE_UNTESTED=0
BMS_REHEARSE_THROWAWAY=''

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

# What the real COMMIT would do and a rolled back transaction never does: the tripwire row goes, then every deferred
# constraint and constraint trigger (the activity log's seal, foreign keys) is checked now.
bms_rehearse_sql_deferred() {
    printf '\\echo @@restricciones diferidas\nDELETE FROM pg_temp.g_c;\nSET CONSTRAINTS ALL IMMEDIATE;\n'
}

# The whole psql script of one database. [steps]: the migrations and grant blocks (psql script text, may be empty).
bms_rehearse_script() {
    local kind=$1 database=$2 owner=$3 runtime=$4 provisioner=$5 owners=$6 control_db=$7 steps=$8 role tenant=0
    # The service keeps running: short limits for everything the rehearsal does (environment overrides in the header).
    local lock_timeout=${BMS_REHEARSE_LOCK_TIMEOUT:-500ms} statement_timeout=${BMS_REHEARSE_STATEMENT_TIMEOUT:-10s}
    local idle_timeout=${BMS_REHEARSE_IDLE_TIMEOUT:-5s} connection_check=${BMS_REHEARSE_CONNECTION_CHECK:-1s}
    local transaction_timeout=${BMS_REHEARSE_TRANSACTION_TIMEOUT:-15s} value
    for value in "$lock_timeout" "$statement_timeout" "$idle_timeout" "$connection_check" "$transaction_timeout"; do
        bms_ascii_match "$value" '^[0-9]{1,6}(ms|s)$' || {
            printf 'ERROR: tiempo limite del ensayo no valido: %q (por ejemplo 500ms, 10s).\n' "$value" >&2
            return 1
        }
    done
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
    # Limits of the session, before the transaction: a statement, an idle moment inside the transaction and (17+) the whole
    # transaction are cut short; a client that vanished (14+) ends it. Whatever they cut is a FAIL, never a PASS.
    printf "SET statement_timeout = '%s';\nSET idle_in_transaction_session_timeout = '%s';\n" "$statement_timeout" "$idle_timeout"
    printf "SELECT pg_catalog.current_setting('server_version_num')::int >= 140000 AS v14, pg_catalog.current_setting('server_version_num')::int >= 170000 AS v17 \\gset\n"
    printf '\\if :v14\nSET client_connection_check_interval = '"'%s'"';\n\\endif\n\\if :v17\nSET transaction_timeout = '"'%s'"';\n\\endif\n' "$connection_check" "$transaction_timeout"
    # BEGIN, and a tripwire: a deferred foreign key that is violated until the end. A COMMIT, however it is spelled or
    # hidden (END, in a string of statements, after a meta-command), fails on it and rolls everything back.
    printf "BEGIN;\n\\set AUTOCOMMIT off\nCREATE TEMP TABLE g_p (id int PRIMARY KEY);\nCREATE TEMP TABLE g_c (id int REFERENCES g_p DEFERRABLE INITIALLY DEFERRED);\nINSERT INTO g_c VALUES (1);\n"
    printf "SET LOCAL lock_timeout = '%s';\nSET LOCAL search_path = pg_catalog, pg_temp;\n" "$lock_timeout"
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
        bms_rehearse_sql_deferred
        printf 'SET LOCAL search_path = pg_catalog, pg_temp;\n'
        printf '\\echo @@acceso tras las migraciones\n'
        bms_sql_verify_runtime
        bms_rehearse_sql_access
    fi
    [[ -n $steps ]] || bms_rehearse_sql_deferred
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
    local started elapsed_ms code
    label="ensayo $kind $database"
    if declare -F bms_rehearse_steps_sql >/dev/null; then
        steps=$(bms_rehearse_steps_sql "$kind" "$database" 2> "$work/steps.err") || {
            bms_rehearse_say FAIL "$label" "no se pudo preparar el SQL de las migraciones: $(head -n 3 "$work/steps.err" | paste -sd ' ' -)"
            return 1
        }
    fi
    # What would end the transaction or leave it is refused before anything runs: transaction control anywhere in a line
    # (indented, after another statement), and every psql meta-command except the ones the migrations use (\set, \if,
    # \elif, \else, \endif, \echo; never \set AUTOCOMMIT). "END;" is also the end of a plpgsql block, so it is not
    # matched here: the tripwire in the script (a deferred foreign key violated until the end) fails any COMMIT, the
    # transaction id is checked at the end, and the snapshots of the whole run are the last line of defence.
    if LC_ALL=C grep -Eiq '(^|;)[[:space:]]*(COMMIT|ROLLBACK|ABORT|SAVEPOINT|RELEASE|PREPARE[[:space:]]+TRANSACTION|START[[:space:]]+TRANSACTION|BEGIN)([[:space:]][^;]*)?;' <<< "$steps"; then
        bms_rehearse_say FAIL "$label" 'el SQL de las migraciones contiene control de transaccion (COMMIT, ROLLBACK, BEGIN...); no se ejecuta'
        return 1
    fi
    # shellcheck disable=SC1003 # the backslash is the pattern
    if LC_ALL=C grep -E '^[[:space:]]*\\' <<< "$steps" | LC_ALL=C grep -Eqv '^[[:space:]]*\\(set|if|elif|else|endif|echo)([[:space:]]|$)' ||
        LC_ALL=C grep -Eiq '^[[:space:]]*\\set[[:space:]]+(AUTOCOMMIT|ON_ERROR_STOP|ECHO)' <<< "$steps"; then
        bms_rehearse_say FAIL "$label" 'el SQL de las migraciones contiene una orden de psql no permitida (\c, \!, \copy...); no se ejecuta'
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
        started=$(date +%s%N)
        bms_pg_query "$database" -v VERBOSITY=verbose < "$work/script.sql" > "$work/out" 2> "$work/err" || status=$?
        (( status != 0 )) || break
        # Only a lock that could not be taken (55P03) is tried again, and never after a signal (128 + n).
        if (( status < 128 )) && LC_ALL=C grep -Eq '(ERROR|FATAL):  55P03' "$work/err" && (( attempt < attempts )); then
            sleep "${BMS_REHEARSE_RETRY_SLEEP:-2}"
            continue
        fi
        # (|| true: with set -e and pipefail a grep that finds nothing must not end the run)
        step=$(LC_ALL=C grep '^@@' "$work/out" | tail -n 1) || true
        step=${step#@@}
        error=$(LC_ALL=C grep -m 1 -E '(ERROR|FATAL):' "$work/err" | LC_ALL=C sed -E 's/^.*(ERROR|FATAL):  *//') || true
        code=$(LC_ALL=C sed -nE 's/^.*(ERROR|FATAL):  ([0-9A-Z]{5}):.*$/\2/p' "$work/err" | head -n 1) || true
        [[ -n $error ]] || error=$(head -n 2 "$work/err" | paste -sd ' ' -)
        if (( status >= 128 )); then
            bms_rehearse_say FAIL "$label" "interrumpido (senal $((status - 128))) en '${step:-inicio}'"
        elif [[ $code == 55P03 ]]; then
            BMS_REHEARSE_UNTESTED=$((BMS_REHEARSE_UNTESTED + 1))
            bms_rehearse_say WARN "$label" "no ensayado por bloqueo: $attempts intentos sin obtener los bloqueos en ${BMS_REHEARSE_LOCK_TIMEOUT:-500ms} (en: ${step:-inicio}); el servicio esta usando esas tablas, repite el ensayo en un momento tranquilo"
        elif [[ $code == 57014 || $code == 25P03 || $code == 25P04 || $code == 57P01 || $code == 57P02 ]]; then
            bms_rehearse_say FAIL "$label" "tiempo agotado o cancelado ($code) en '${step:-inicio}': $error; si es una migracion pesada sube BMS_REHEARSE_STATEMENT_TIMEOUT / BMS_REHEARSE_TRANSACTION_TIMEOUT (ahora ${BMS_REHEARSE_STATEMENT_TIMEOUT:-10s} / ${BMS_REHEARSE_TRANSACTION_TIMEOUT:-15s})"
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
    elapsed_ms=$((($(date +%s%N) - started) / 1000000))
    bms_rehearse_say PASS "$label" "ejecutado y revertido (ROLLBACK) en $elapsed_ms ms (los bloqueos se mantuvieron como maximo ese tiempo); $(head -n 1 "$work/diff" | LC_ALL=C sed 's/^TOTAL //')"
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

# The rehearsal. [control_db] [runtime] [provisioner]: as update.sh would use them; [host] [port]: DB_URL's, for the login
# checks (empty: those are skipped). Uses [work] for its files.
bms_rehearse_run_in() {
    local control_db=$1 runtime=$2 provisioner=$3 work=$4 host=${5:-} port=${6:-} owners owners_text classified tenants kind name rest
    local assessment layout pending done_count attempted attempts_seen=0 touched=0 mixed=0 database owner n
    BMS_REHEARSE_FAILS=0
    BMS_REHEARSE_WARNS=0
    BMS_REHEARSE_UNTESTED=0
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
    if [[ -n $host && -n $port ]]; then
        bms_rehearse_login_checks "$control_db" "$provisioner" "$host" "$port" "$work" || true
    else
        bms_rehearse_say INFO 'login de aprovisionamiento' 'servidor y puerto de DB_URL no indicados: no se comprueba pg_hba ni el login TCP'
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
    bms_rehearse_say INFO 'NO ensayado' 'la contrasena real y el login real de '"$provisioner"' (solo un rol de prueba por el mismo transporte), el arranque de la version nueva, compilacion, Nginx, systemd, la carga del servicio durante el cambio real y datos que el servicio escriba entre fases'
    if (( BMS_REHEARSE_FAILS == 0 && BMS_REHEARSE_UNTESTED > 0 )); then
        bms_rehearse_say WARN 'ensayo incompleto' "$BMS_REHEARSE_UNTESTED base(s) no se ensayaron por bloqueo: el ensayo NO vale como prueba; repitelo en un momento tranquilo"
        return 2
    fi
    (( BMS_REHEARSE_FAILS == 0 ))
}

# --- The provisioning login: what the real update will need at the DB_URL host ------------------------------------------

# Addresses a host name resolves to, in the order libpq tries them (a literal address is itself), one per line.
bms_rehearse_resolve() {
    python3 -c '
import socket, sys
host = sys.argv[1]
seen = []
for info in socket.getaddrinfo(host, None, socket.AF_UNSPEC, socket.SOCK_STREAM):
    if info[4][0] not in seen:
        seen.append(info[4][0])
print("\n".join(seen))' "$1"
}

# The pg_hba rule that applies to [user] connecting to [database] from [ip] (first match wins, as in PostgreSQL), given the
# rows of pg_hba_file_rules on stdin as type|databases|users|address|netmask|method. Prints the method, or "none".
bms_rehearse_hba_match() {
    python3 -c '
import ipaddress, sys
ip, user, db, host = ipaddress.ip_address(sys.argv[1].split("%")[0]), sys.argv[2], sys.argv[3], sys.argv[4]
for line in sys.stdin:
    fields = line.rstrip("\n").split("|")
    if len(fields) != 6 or not fields[0].startswith("host"):
        continue
    kind, dbs, users, addr, mask, method = fields
    if not ("all" in dbs.split(",") or db in dbs.split(",") or ("sameuser" in dbs.split(",") and db == user)):
        continue
    if not ("all" in users.split(",") or user in users.split(",")):
        continue
    if addr == "all":
        pass
    elif addr in ("samehost", "samenet"):
        if not ip.is_loopback:
            continue
    else:
        try:
            prefix = bin(int(ipaddress.ip_address(mask))).count("1") if mask else None
            net = ipaddress.ip_network((addr, prefix) if mask else addr, strict=False)
        except ValueError:
            if addr != host:
                continue
        else:
            if ip.version != net.version or ip not in net:
                continue
    print(method)
    sys.exit(0)
print("none")' "$1" "$2" "$3" "$4"
}

# A throwaway LOGIN role with a random password (outside the rolled back transactions), dropped here and by the traps of
# bms_rehearse_run on any exit or signal.
bms_rehearse_drop_throwaway() {
    local role=$BMS_REHEARSE_THROWAWAY
    [[ -n $role ]] || return 0
    bms_valid_role "$role" || return 1
    printf 'DROP ROLE IF EXISTS "%s";\n' "$role" | bms_pg_exec postgres > /dev/null 2>&1 || return 1
    BMS_REHEARSE_THROWAWAY=''
}

# Real TCP login as the provisioner will do it: same host string, port, passfile (PGPASSFILE), connect timeout and sslmode
# (the environment's), with a throwaway role: the real one's password is not known to the rehearsal.
bms_rehearse_tcp_login() {
    local host=$1 port=$2 work=$3 name password verifier out='' status=0
    name=bms_rh_$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')
    password=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
    bms_valid_role "$name" || return 1
    BMS_REHEARSE_THROWAWAY=$name
    verifier=$(printf '%s' "$password" | bms_scram_verifier) || return 1
    printf 'CREATE ROLE "%s" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"'"'%s'"'"';\n' "$name" "$verifier" | bms_pg_exec postgres || return 1
    printf '%s:%s:*:%s:%s\n' "$host" "$port" "$name" "$password" > "$work/pgpass"
    chmod 600 "$work/pgpass"
    if [[ ${BMS_TEST_FAIL_AT:-} == rehearse_throwaway_wait ]]; then
        sleep 30 &
        wait $!
    fi
    if bms_failpoint rehearse_throwaway_fail; then
        out=$(PGPASSFILE=$work/pgpass PGCONNECT_TIMEOUT=15 psql -X -q -A -t -w -h "$host" -p "$port" -U "$name" -d postgres -c 'SELECT 1' 2> "$work/login.err") || status=$?
    else
        status=1
        printf 'fallo inyectado\n' > "$work/login.err"
    fi
    bms_rehearse_drop_throwaway || printf 'AVISO: no se pudo borrar el rol de prueba %s; borralo: DROP ROLE "%s";\n' "$name" "$name" >&2
    [[ $status == 0 && $out == 1 ]]
}

# pg_hba for the provisioner at the DB_URL host, then a real login. Lines for the report; FAIL when the real update would
# stop at its first login (which comes after the role move, so it matters here).
bms_rehearse_login_checks() {
    local control_db=$1 provisioner=$2 host=$3 port=$4 work=$5 addresses address rows method first=1
    if ! addresses=$(bms_rehearse_resolve "$host" 2> "$work/err"); then
        bms_rehearse_say FAIL "login de $provisioner" "el servidor $host de DB_URL no resuelve: $(head -n 1 "$work/err")"
        return 1
    fi
    rows=$(bms_pg_query postgres -c "SELECT type || '|' || array_to_string(database, ',') || '|' || array_to_string(user_name, ',') || '|' || COALESCE(address, '') || '|' || COALESCE(netmask, '') || '|' || COALESCE(auth_method, '') FROM pg_catalog.pg_hba_file_rules WHERE error IS NULL ORDER BY rule_number") || {
        bms_rehearse_say FAIL "pg_hba de $provisioner" 'no se pudo leer pg_hba_file_rules'
        return 1
    }
    while IFS= read -r address; do
        [[ -n $address ]] || continue
        method=$(bms_rehearse_hba_match "$address" "$provisioner" "$control_db" "$host" <<< "$rows")
        case $method in
            scram-sha-256|md5|password)
                bms_rehearse_say PASS "pg_hba de $provisioner" "$host ($address, $([[ $first == 1 ]] && echo 'la primera que prueba libpq' || echo 'otra direccion')): regla $method" ;;
            trust)
                bms_rehearse_say WARN "pg_hba de $provisioner" "$host ($address): regla trust (entra sin contrasena)" ;;
            none)
                if (( first == 1 )); then
                    bms_rehearse_say FAIL "pg_hba de $provisioner" "$host ($address, la primera que prueba libpq): ninguna regla host para ese rol y base; el login falla DESPUES del cambio de roles. Agrega una regla scram-sha-256 para $provisioner en esa direccion"
                else
                    bms_rehearse_say WARN "pg_hba de $provisioner" "$host ($address): sin regla; fallaria si libpq cae a esa direccion"
                fi ;;
            *)
                bms_rehearse_say FAIL "pg_hba de $provisioner" "$host ($address): la regla que aplica es $method, no una de contrasena" ;;
        esac
        first=0
    done <<< "$addresses"
    if bms_rehearse_tcp_login "$host" "$port" "$work"; then
        bms_rehearse_say PASS "login TCP como el aprovisionador" "rol de prueba con contrasena scram entro por $host:$port con passfile (sslmode ${PGSSLMODE:-prefer}) y se borro"
    else
        bms_rehearse_say FAIL "login TCP como el aprovisionador" "un rol de prueba con contrasena no pudo entrar por $host:$port con passfile: $(head -n 2 "$work/login.err" 2> /dev/null | paste -sd ' ' -); el login real fallaria despues del cambio de roles"
        bms_rehearse_drop_throwaway || true
    fi
}

# The rehearsal in a subshell with its own traps (the caller's are untouched): the work directory and the throwaway role
# are removed on any exit, Ctrl-C included, which also stops the loop (nothing is retried after a signal).
bms_rehearse_run() {
    local work counts status=0 numbers
    work=$(mktemp -d) || return 1
    counts=$(mktemp) || return 1
    (
        # shellcheck disable=SC2064 # the directory is fixed now
        trap "bms_rehearse_drop_throwaway || true; rm -rf --one-file-system -- '$work'" EXIT
        trap 'exit 130' INT
        trap 'exit 143' TERM
        status=0
        bms_rehearse_run_in "$1" "$2" "$3" "$work" "${4:-}" "${5:-}" || status=$?
        printf '%s %s %s\n' "$BMS_REHEARSE_FAILS" "$BMS_REHEARSE_WARNS" "$BMS_REHEARSE_UNTESTED" > "$counts"
        exit "$status"
    ) || status=$?
    numbers=$(cat "$counts" 2> /dev/null) || numbers=''
    rm -f -- "$counts"
    # shellcheck disable=SC2034 # read by update.sh (summary line)
    read -r BMS_REHEARSE_FAILS BMS_REHEARSE_WARNS BMS_REHEARSE_UNTESTED <<< "${numbers:-0 0 0}"
    return "$status"
}
