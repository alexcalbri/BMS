#!/usr/bin/env bash
# PostgreSQL role layout of BMS (SELF_HOSTING.md "Roles de PostgreSQL"), sourced by install.sh, update.sh and
# preflight.sh.
#
#   runtime role (DB_USER, e.g. bms_app)   the long-lived application connections. A plain login role: no
#                                          membership in any other role, no CREATEDB/CREATEROLE, owns nothing;
#                                          only the grants it needs (DML on ordinary tables; SELECT, INSERT on the
#                                          activity log and its chain and on tenant_data_keys, USAGE on the log's
#                                          sequence).
#   server owner role (e.g. server_owner)  the server owner's login (their password). CREATEDB CREATEROLE; owns
#                                          every company database and every object in it.
#   provisioning role (DB_PROVISIONING_USER, e.g. bms_provisioner)
#                                          used only by company provisioning and migrations, on short-lived
#                                          connections of their own. NOINHERIT: it holds nothing until it runs
#                                          SET ROLE to the server owner's role (to create or migrate a company) or
#                                          to the runtime role (to write an activity row). NOCREATEDB NOCREATEROLE.
#
# THE ONE RULE ABOUT psql OUTPUT. Nothing in BMS parses text that psql printed by accident. The caller defines only
# the transport,
#
#   bms_pg_transport <database> [psql arguments...]     psql as the PostgreSQL superuser connected to <database>,
#                                                       SQL on stdin; it adds nothing about output or errors,
#
# and every call in this file goes through one of two helpers that fix the flags in ONE place:
#
#   bms_pg_query <database> [options]   data out: rows only (-q -A -t: no command tags such as "SET", no headers, no
#                                       footers), -X (no .psqlrc), ON_ERROR_STOP. Use it whenever stdout is captured
#                                       or parsed. Even so, a value that becomes a role or database name is validated
#                                       where it is used (bms_valid_role and friends): a stray token never reaches
#                                       GRANT or ALTER.
#   bms_pg_exec <database> [options]    statements in, nothing out (-q -X, ON_ERROR_STOP). Never captured.
#
# The live failure this prevents: a SET statement before a SELECT made psql print the command tag "SET" before the
# role name (the transport of update.sh had no -q while the test transport had), and "SET" was then used as a role.
#
# Every query here pins search_path = pg_catalog, pg_temp and schema-qualifies what it touches, and only reads
# catalogs or runs GRANT, REVOKE, ALTER ROLE and ALTER ... OWNER: nothing a company database defines is executed.
# The SQL of every step is produced by a bms_sql_* function and passed to psql as one script, so scripts/ubuntu/
# rehearse.sh can run exactly the same text inside a transaction that is rolled back.

# Sourced: the checks here never rely on the caller's locale (bms_ascii_match, LC_ALL=C on sort and the like).
# [[ =~ ]] with LC_ALL=C for this one test, whatever the caller's locale: [a-z], [0-9a-f] or [!-~] mean ASCII.
bms_ascii_match() { local LC_ALL=C; [[ $1 =~ $2 ]]; }

readonly BMS_ROLE_PATTERN='^[a-z][a-z0-9_]{0,62}$'
readonly BMS_DATABASE_PATTERN='^[a-z][a-z0-9_]{0,62}$'
readonly BMS_TENANT_DB_PATTERN='^bms_[a-z0-9_]{1,59}$'

bms_valid_role() { bms_ascii_match "$1" "$BMS_ROLE_PATTERN" && [[ $1 != pg_* && $1 != postgres ]]; }
bms_valid_control_db() { bms_ascii_match "$1" "$BMS_DATABASE_PATTERN"; }
bms_valid_tenant_db() { bms_ascii_match "$1" "$BMS_TENANT_DB_PATTERN"; }

# --- Transport, query and exec ---------------------------------------------------------------------------------------

bms_pg_require_transport() {
    declare -F bms_pg_transport >/dev/null && return 0
    printf 'ERROR: falta bms_pg_transport: update.sh, install.sh o preflight.sh y db-roles.sh no son de la misma version (descarga todos de la misma referencia).\n' >&2
    return 1
}

# Rows only. -q: no command tags; -A -t: unaligned, no header, no footer; -X: no .psqlrc; the options of the caller
# come first and cannot weaken these (the later -v wins).
bms_pg_query() {
    local database=$1
    shift
    bms_pg_require_transport || return 1
    PSQLRC=/dev/null bms_pg_transport "$database" "$@" -X -q -A -t -v ON_ERROR_STOP=1
}

# Statements in, nothing out.
bms_pg_exec() {
    local database=$1
    shift
    bms_pg_require_transport || return 1
    PSQLRC=/dev/null bms_pg_transport "$database" "$@" -X -q -v ON_ERROR_STOP=1
}

# The same for a connection as the provisioning login (the caller defines bms_pg_as_provisioner <database> [args...]).
bms_pg_provisioner_query() {
    local database=$1
    shift
    declare -F bms_pg_as_provisioner >/dev/null || { printf 'ERROR: falta bms_pg_as_provisioner.\n' >&2; return 1; }
    PSQLRC=/dev/null bms_pg_as_provisioner "$database" "$@" -X -q -A -t -v ON_ERROR_STOP=1
}

# One non-negative integer from a query (the arguments of bms_pg_query), or an error that names what came back.
bms_pg_count() {
    local out
    out=$(bms_pg_query "$@") || return 1
    bms_ascii_match "$out" '^[0-9]+$' || {
        printf 'ERROR: PostgreSQL no devolvio un numero sino %q.\n' "$out" >&2
        return 1
    }
    printf '%s\n' "$out"
}

# Test hook: BMS_TEST_FAIL_AT=<name> makes the step <name> fail (the tests inject a failure at each stage of the move
# and check the rollback). update.sh refuses to run with it set; it has no other use.
bms_failpoint() {
    [[ -n ${BMS_TEST_FAIL_AT:-} && ${BMS_TEST_FAIL_AT} == "$1" ]] || return 0
    printf 'ERROR: fallo inyectado en %s (BMS_TEST_FAIL_AT, solo pruebas).\n' "$1" >&2
    return 1
}

# \set <name> '<value>' for a psql script; the value is checked first, so no quote or newline can reach the script.
bms_psql_set() {
    bms_ascii_match "$2" '^[A-Za-z0-9_.:@,/=+-]{0,200}$' || {
        printf 'ERROR: valor no valido para la variable %s: %q.\n' "$1" "$2" >&2
        return 1
    }
    printf '\\set %s %s\n' "$1" "'$2'"
}

# Role names, one per line on stdin (what a query printed): every one must pass bms_valid_role. [what] names the
# list in the error. Prints them unchanged.
bms_roles_validated() {
    local what=$1 line
    while IFS= read -r line; do
        [[ -n $line ]] || continue
        bms_valid_role "$line" || {
            printf 'ERROR: %s: %q no es un nombre de rol valido (a-z, 0-9 y _; no pg_ ni postgres); no se uso.\n' "$what" "$line" >&2
            return 1
        }
        printf '%s\n' "$line"
    done
}

# Roles of the active and inactive server owners (application_users.postgres_role of server_owners), one per
# line, only those that exist as PostgreSQL roles and are not superusers.
bms_server_owner_roles() {
    local control_db=$1 out
    bms_valid_control_db "$control_db" || { printf 'ERROR: base central no valida: %q.\n' "$control_db" >&2; return 1; }
    out=$(bms_pg_query "$control_db" <<'SQL'
SET search_path = pg_catalog, pg_temp;
SELECT DISTINCT r.rolname FROM public.server_owners o
JOIN public.application_users u ON u.id = o.user_id
JOIN pg_catalog.pg_roles r ON r.rolname = u.postgres_role
WHERE NOT r.rolsuper AND r.rolname !~ '^pg_' ORDER BY 1;
SQL
) || return 1
    bms_roles_validated "roles de server owner de $control_db" <<< "$out"
}

# Every database registered in companies.database_name (which the application can write), classified, one line each:
#   ok <database> <owner role>   matches bms_<code>, is not the control database, exists, is not a template and
#                                belongs to a server owner's role: update.sh may migrate it
#   missing <database>           registered but does not exist (nothing there to break)
#   reject <database> <reason>   exists but fails a rule: update.sh must not touch it, and must not change the roles
#                                either (the company would lose its access)
bms_classify_tenant_databases() {
    local control_db=$1 owners listed name owner exists
    owners=$(bms_server_owner_roles "$control_db") || return 1
    listed=$(bms_pg_query "$control_db" -c 'SELECT database_name FROM public.companies ORDER BY code') || return 1
    while IFS= read -r name; do
        [[ -n $name ]] || continue
        exists=$(bms_pg_count postgres -v name="$name" <<'SQL'
SELECT count(*) FROM pg_catalog.pg_database WHERE datname = :'name';
SQL
) || return 1
        if [[ $exists == 0 ]]; then
            printf 'missing %s\n' "$name"
            continue
        fi
        if ! bms_valid_tenant_db "$name" || [[ $name == "$control_db" ]]; then
            printf 'reject %s no es un nombre de empresa (bms_<codigo>) o es la base central\n' "$name"
            continue
        fi
        owner=$(bms_pg_query postgres -v name="$name" <<'SQL'
SELECT pg_catalog.pg_get_userbyid(datdba) FROM pg_catalog.pg_database WHERE datname = :'name' AND NOT datistemplate;
SQL
) || return 1
        if [[ -z $owner ]]; then
            printf 'reject %s es una plantilla\n' "$name"
        elif ! grep -Fxq -- "$owner" <<< "$owners"; then
            printf 'reject %s pertenece a %s, que no es el rol de un server owner\n' "$name" "$owner"
        else
            bms_valid_role "$owner" || { printf 'ERROR: dueno de %s no valido: %q.\n' "$name" "$owner" >&2; return 1; }
            printf 'ok %s %s\n' "$name" "$owner"
        fi
    done <<< "$listed"
}

# Company databases that update.sh may migrate, as "<database> <owner role>" lines (the "ok" ones above). The others
# are reported on stderr and left alone.
bms_tenant_databases() {
    local control_db=$1 lines kind name rest
    lines=$(bms_classify_tenant_databases "$control_db") || return 1
    while read -r kind name rest; do
        case $kind in
            ok) printf '%s %s\n' "$name" "$rest" ;;
            missing) printf 'AVISO: se omite la base "%s": no existe.\n' "$name" >&2 ;;
            reject) printf 'AVISO: se omite la base "%s": %s.\n' "$name" "$rest" >&2 ;;
        esac
    done <<< "$lines"
}

# SCRAM-SHA-256 verifier of the password read from stdin (never an argument). Printable ASCII avoids SASLprep.
# shellcheck disable=SC2016
bms_scram_verifier() { python3 -c '
import base64, hashlib, hmac, os, sys
b64 = lambda data: base64.b64encode(data).decode("ascii")
salt = os.urandom(16)
key = hashlib.pbkdf2_hmac("sha256", sys.stdin.buffer.read(), salt, 4096)
stored = hashlib.sha256(hmac.digest(key, b"Client Key", "sha256")).digest()
server = hmac.digest(key, b"Server Key", "sha256")
print("SCRAM-SHA-256$4096:" + b64(salt) + "$" + b64(stored) + ":" + b64(server))
'; }

readonly BMS_PROVISIONER_ATTRIBUTES='NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
readonly BMS_RUNTIME_ATTRIBUTES='NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'

# Creates the role <name> (LOGIN, the given attributes) or sets its password, with the password read from stdin.
# The verifier only travels inside the SQL on psql's stdin, never on a command line.
bms_role_set_password() {
    local name=$1 attributes=$2 verifier exists
    bms_valid_role "$name" || { printf 'ERROR: rol no valido: %q.\n' "$name" >&2; return 1; }
    verifier=$(bms_scram_verifier) || return 1
    [[ $verifier == SCRAM-SHA-256\$* && $verifier != *"'"* ]] || return 1
    exists=$(bms_pg_count postgres -v name="$name" <<'SQL'
SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname = :'name';
SQL
) || return 1
    if [[ $exists == 0 ]]; then
        printf 'CREATE ROLE "%s" LOGIN %s PASSWORD '"'"'%s'"'"';\n' "$name" "$attributes" "$verifier" | bms_pg_exec postgres
    else
        printf 'ALTER ROLE "%s" LOGIN %s PASSWORD '"'"'%s'"'"';\n' "$name" "$attributes" "$verifier" | bms_pg_exec postgres
    fi
}

# --- SQL of each step (text only: nothing runs here) -------------------------------------------------------------------
# Scripts use psql variables set with bms_psql_set: runtime, provisioner, owner, role, member, db, tenant, tag.

# The provisioning role's attributes. With create, a missing role is created (the rehearsal; the real run has it
# already, with its password).
bms_sql_provisioner_role() {
    if [[ ${1:-} == create ]]; then
        cat <<SQL
SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'provisioner') AS provisioner_exists \\gset
\\if :provisioner_exists
ALTER ROLE :"provisioner" LOGIN $BMS_PROVISIONER_ATTRIBUTES;
\\else
CREATE ROLE :"provisioner" LOGIN $BMS_PROVISIONER_ATTRIBUTES;
\\endif
SQL
    else
        printf 'ALTER ROLE :"provisioner" LOGIN %s;\n' "$BMS_PROVISIONER_ATTRIBUTES"
    fi
}

# SET (never INHERIT) membership of [member] in [role]. PostgreSQL 16+ records the options per grant; older versions
# take them from the member's NOINHERIT attribute.
bms_sql_grant_set_only() {
    cat <<'SQL'
SELECT pg_catalog.current_setting('server_version_num')::int >= 160000 AS pg16 \gset
\if :pg16
GRANT :"role" TO :"member" WITH INHERIT FALSE, SET TRUE;
\else
GRANT :"role" TO :"member";
\endif
SQL
}

# The old layout back for [member] in [role]: a plain member that inherits and can SET (what install.sh made).
bms_sql_restore_membership() {
    cat <<'SQL'
SELECT pg_catalog.current_setting('server_version_num')::int >= 160000 AS pg16 \gset
\if :pg16
GRANT :"role" TO :"member" WITH INHERIT TRUE, SET TRUE;
\else
GRANT :"role" TO :"member";
\endif
SQL
}

bms_sql_connect_grant() { printf 'GRANT CONNECT ON DATABASE :"db" TO :"member";\n'; }

# Every REVOKE of [role] from [member], whoever granted it.
bms_sql_revoke_membership() {
    cat <<'SQL'
SELECT pg_catalog.format('REVOKE %I FROM %I GRANTED BY %I', :'role', :'member', pg_catalog.pg_get_userbyid(m.grantor))
FROM pg_catalog.pg_auth_members m
WHERE m.roleid = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = :'role')
  AND m.member = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = :'member')
\gexec
SQL
}

bms_sql_runtime_attributes() { printf 'ALTER ROLE :"runtime" %s;\n' "$BMS_RUNTIME_ATTRIBUTES"; }

# The three queries (without terminator) that produce the ALTER ... OWNER statements of a company database. Objects
# of schema public owned by a superuser (an older update.sh) or by the runtime role go to the database owner, so
# later migrations run as that role. Objects of extensions and sequences that belong to a column move with their
# extension or table. Every object of the activity log moves together (its owners stay one role). Variables: owner,
# runtime. The same text feeds the move (followed by \gexec) and the count of what is still pending.
bms_sql_move_class_select() {
    cat <<'SQL'
SELECT pg_catalog.format('ALTER %s %I.%I OWNER TO %I',
           CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' WHEN 'f' THEN 'FOREIGN TABLE'
                          WHEN 'S' THEN 'SEQUENCE' WHEN 'c' THEN 'TYPE' ELSE 'TABLE' END,
           n.nspname, c.relname, :'owner')
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
JOIN pg_catalog.pg_roles r ON r.oid = c.relowner
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S', 'c')
  AND (r.rolsuper OR r.rolname = :'runtime')
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass AND d.objid = c.oid AND d.deptype = 'e')
  AND NOT (c.relkind = 'S' AND EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass AND d.objid = c.oid
                    AND d.refclassid = 'pg_catalog.pg_class'::pg_catalog.regclass AND d.deptype IN ('a', 'i')))
ORDER BY c.relkind = 'S', c.relname
SQL
}

bms_sql_move_proc_select() {
    cat <<'SQL'
SELECT pg_catalog.format('ALTER %s %s OWNER TO %I', CASE f.prokind WHEN 'a' THEN 'AGGREGATE' ELSE 'ROUTINE' END,
           f.oid::pg_catalog.regprocedure, :'owner')
FROM pg_catalog.pg_proc f
JOIN pg_catalog.pg_namespace n ON n.oid = f.pronamespace
JOIN pg_catalog.pg_roles r ON r.oid = f.proowner
WHERE n.nspname = 'public' AND (r.rolsuper OR r.rolname = :'runtime')
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_proc'::pg_catalog.regclass AND d.objid = f.oid AND d.deptype = 'e')
ORDER BY f.oid
SQL
}

bms_sql_move_type_select() {
    cat <<'SQL'
SELECT pg_catalog.format('ALTER %s %s OWNER TO %I', CASE t.typtype WHEN 'd' THEN 'DOMAIN' ELSE 'TYPE' END,
           t.oid::pg_catalog.regtype, :'owner')
FROM pg_catalog.pg_type t
JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
JOIN pg_catalog.pg_roles r ON r.oid = t.typowner
WHERE n.nspname = 'public' AND t.typtype IN ('e', 'd', 'r') AND t.typrelid = 0
  AND (r.rolsuper OR r.rolname = :'runtime')
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_type'::pg_catalog.regclass AND d.objid = t.oid AND d.deptype = 'e')
ORDER BY t.oid
SQL
}

# Grants the runtime role, explicitly, every DML privilege it holds today on the objects of one company database
# (until now partly through its membership in the owner's role), then gives what an older update.sh created as the
# superuser (or what the runtime role itself owns) to the database owner, and leaves the activity log exactly
# SELECT, INSERT (USAGE on its sequence) for the runtime role. Runs inside the caller's transaction. Running it
# again changes nothing.
bms_sql_tenant_layout() {
    cat <<'SQL'
SELECT oid AS runtime_oid FROM pg_catalog.pg_roles WHERE rolname = :'runtime' \gset
-- 1. What the runtime role can do now (directly or as a member), granted directly, on tables, views and sequences.
SELECT pg_catalog.format('GRANT %s ON TABLE %I.%I TO %I', p.privilege, n.nspname, c.relname, :'runtime')
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) AS p (privilege)
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
  AND (c.relkind <> 'm' OR p.privilege = 'SELECT')
  AND c.relname NOT IN ('activity_log', 'activity_log_chain')
  AND NOT (c.relname = 'tenant_data_keys' AND p.privilege IN ('UPDATE', 'DELETE'))
  AND NOT (c.relname = 'application_user_2fa_email_recovery' AND p.privilege = 'DELETE')
  AND pg_catalog.has_table_privilege(:'runtime', c.oid, p.privilege)
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.aclexplode(c.relacl) a
                  WHERE a.grantee = :'runtime_oid'::oid AND a.privilege_type = p.privilege)
ORDER BY c.relname, p.privilege
\gexec
SELECT pg_catalog.format('GRANT %s ON SEQUENCE %I.%I TO %I', p.privilege, n.nspname, c.relname, :'runtime')
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN (VALUES ('USAGE'), ('SELECT'), ('UPDATE')) AS p (privilege)
WHERE n.nspname = 'public' AND c.relkind = 'S' AND c.relname <> 'activity_log_id_seq'
  AND CASE WHEN c.relkind = 'S' THEN pg_catalog.has_sequence_privilege(:'runtime', c.oid, p.privilege) ELSE FALSE END
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.aclexplode(c.relacl) a
                  WHERE a.grantee = :'runtime_oid'::oid AND a.privilege_type = p.privilege)
ORDER BY c.relname, p.privilege
\gexec
-- 2. Ownership (the three queries are bms_sql_move_*_select).
SQL
    bms_sql_move_class_select
    printf '\\gexec\n'
    bms_sql_move_proc_select
    printf '\\gexec\n'
    bms_sql_move_type_select
    printf '\\gexec\n'
    cat <<'SQL'
-- 3. The activity log stays append-only for the runtime role: SELECT, INSERT on the log and its chain, USAGE on
-- the id sequence, nothing else (ACTIVITY_LOG.md section 9).
SELECT pg_catalog.format('REVOKE %s ON TABLE public.%I FROM %I', a.privilege_type, c.relname, :'runtime')
FROM pg_catalog.pg_class c, pg_catalog.aclexplode(c.relacl) a
WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND c.relname IN ('activity_log', 'activity_log_chain')
  AND c.relkind = 'r' AND a.grantee = :'runtime_oid'::oid AND a.privilege_type NOT IN ('SELECT', 'INSERT')
\gexec
SELECT pg_catalog.format('GRANT %s ON TABLE public.%I TO %I', p.privilege, c.relname, :'runtime')
FROM pg_catalog.pg_class c CROSS JOIN (VALUES ('SELECT'), ('INSERT')) AS p (privilege)
WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND c.relname IN ('activity_log', 'activity_log_chain')
  AND c.relkind = 'r'
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.aclexplode(c.relacl) a
                  WHERE a.grantee = :'runtime_oid'::oid AND a.privilege_type = p.privilege)
\gexec
SELECT pg_catalog.format('REVOKE %s ON SEQUENCE public.activity_log_id_seq FROM %I', a.privilege_type, :'runtime')
FROM pg_catalog.pg_class c, pg_catalog.aclexplode(c.relacl) a
WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND c.relname = 'activity_log_id_seq' AND c.relkind = 'S'
  AND a.grantee = :'runtime_oid'::oid AND a.privilege_type <> 'USAGE'
\gexec
SELECT pg_catalog.format('GRANT USAGE ON SEQUENCE public.activity_log_id_seq TO %I', :'runtime')
FROM pg_catalog.pg_class c
WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND c.relname = 'activity_log_id_seq' AND c.relkind = 'S'
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.aclexplode(c.relacl) a
                  WHERE a.grantee = :'runtime_oid'::oid AND a.privilege_type = 'USAGE')
\gexec
-- 4. The company's data key (tenant V006) is only read and created by the runtime role: replacing or deleting it
-- would make every sealed secret unreadable.
SELECT pg_catalog.format('REVOKE %s ON TABLE public.tenant_data_keys FROM %I', a.privilege_type, :'runtime')
FROM pg_catalog.pg_class c, pg_catalog.aclexplode(c.relacl) a
WHERE c.oid = pg_catalog.to_regclass('public.tenant_data_keys')
  AND a.grantee = :'runtime_oid'::oid AND a.privilege_type NOT IN ('SELECT', 'INSERT')
\gexec
SQL
}

# Control database: everything there belongs to the superuser; the runtime keeps its explicit grants, and the server
# audit and the data key of control V017 stay SELECT, INSERT for it. Inside the caller's transaction.
bms_sql_control_layout() {
    cat <<'SQL'
SELECT oid AS runtime_oid FROM pg_catalog.pg_roles WHERE rolname = :'runtime' \gset
SELECT pg_catalog.format('GRANT %s ON TABLE public.%I TO %I', p.privilege, c.relname, :'runtime')
FROM pg_catalog.pg_class c CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) AS p (privilege)
WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND c.relkind IN ('r', 'p', 'v')
  AND c.relname <> 'provisioning_audit_log'
  AND NOT (c.relname = 'tenant_data_keys' AND p.privilege IN ('UPDATE', 'DELETE'))
  AND NOT (c.relname = 'application_user_2fa_email_recovery' AND p.privilege = 'DELETE')
  AND pg_catalog.has_table_privilege(:'runtime', c.oid, p.privilege)
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.aclexplode(c.relacl) a
                  WHERE a.grantee = :'runtime_oid'::oid AND a.privilege_type = p.privilege)
\gexec
SELECT pg_catalog.format('REVOKE %s ON TABLE public.%I FROM %I', a.privilege_type, c.relname, :'runtime')
FROM pg_catalog.pg_class c, pg_catalog.aclexplode(c.relacl) a
WHERE c.oid IN (pg_catalog.to_regclass('public.provisioning_audit_log'), pg_catalog.to_regclass('public.tenant_data_keys'))
  AND a.grantee = :'runtime_oid'::oid AND a.privilege_type NOT IN ('SELECT', 'INSERT')
\gexec
SQL
}

# The problems (one text row each) of the final layout: the runtime role holds no membership and no ownership-like
# attribute. Variables: runtime, provisioner. No terminator (the caller adds ";" or wraps it in a subquery).
bms_sql_layout_problems() {
    cat <<'SQL'
SELECT 'el rol ' || :'runtime' || ' es miembro de ' || r.rolname || ', que crea bases o roles o es dueno de una base'
FROM pg_catalog.pg_auth_members m JOIN pg_catalog.pg_roles r ON r.oid = m.roleid
WHERE m.member = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = :'runtime')
  AND (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR EXISTS (SELECT 1 FROM pg_catalog.pg_database d WHERE d.datdba = r.oid))
UNION ALL
SELECT 'el rol ' || rolname || ' tiene SUPERUSER, CREATEDB o CREATEROLE' FROM pg_catalog.pg_roles
WHERE rolname IN (:'runtime', :'provisioner') AND (rolsuper OR rolcreatedb OR rolcreaterole)
UNION ALL
SELECT 'el rol ' || rolname || ' hereda privilegios (INHERIT)' FROM pg_catalog.pg_roles
WHERE rolname = :'provisioner' AND rolinherit
SQL
}

# The runtime role, via SET ROLE from the superuser (so every privilege check is its own), connects to and reads
# application_users in the database [db] and, with tenant = 1, the activity log and its chain (when the company has
# them yet). Fails (division by
# zero) when it cannot connect. Variables: runtime, db, tenant.
bms_sql_verify_runtime() {
    cat <<'SQL'
SELECT pg_catalog.has_database_privilege(:'runtime', :'db', 'CONNECT') AS can_connect \gset
\if :can_connect
\else
SELECT 1 / 0 AS sin_connect;
\endif
SET ROLE :"runtime";
SELECT count(*) >= 0 FROM public.application_users;
\if :tenant
-- A company that has not got tenant V011 yet (its migrations come after the role move) has no activity log to read.
SELECT pg_catalog.to_regclass('public.activity_log') IS NOT NULL AND pg_catalog.to_regclass('public.activity_log_chain') IS NOT NULL AS has_log \gset
\if :has_log
SELECT count(*) >= 0 FROM (SELECT 1 FROM public.activity_log LIMIT 1) AS l;
SELECT count(*) >= 0 FROM (SELECT 1 FROM public.activity_log_chain LIMIT 1) AS c;
\endif
\endif
RESET ROLE;
SQL
}

# Snapshots of everything a role layout move may touch, one row per fact: <tag>|<kind>|<key>|<value>. Variable: tag.
# Global: roles (attributes and a hash of the password verifier), memberships, databases (owner, ACL). Columns of
# pg_auth_members come from to_jsonb, so the options of PostgreSQL 16+ are included and older versions still work.
bms_sql_snapshot_global() {
    cat <<'SQL'
SELECT :'tag' || '|role|' || r.rolname || '|' || concat_ws(',', r.rolsuper, r.rolinherit, r.rolcreaterole, r.rolcreatedb,
       r.rolcanlogin, r.rolreplication, r.rolbypassrls, r.rolconnlimit, COALESCE(r.rolvaliduntil::text, ''),
       COALESCE(r.rolconfig::text, ''), COALESCE(pg_catalog.md5(a.rolpassword), ''))
FROM pg_catalog.pg_roles r JOIN pg_catalog.pg_authid a ON a.oid = r.oid
UNION ALL
SELECT :'tag' || '|member|' || pg_catalog.pg_get_userbyid(m.member) || '>' || pg_catalog.pg_get_userbyid(m.roleid) || '|'
       || (pg_catalog.to_jsonb(m) - 'oid' - 'roleid' - 'member' - 'grantor')::text
       || ' por ' || pg_catalog.pg_get_userbyid(m.grantor)
FROM pg_catalog.pg_auth_members m
UNION ALL
SELECT :'tag' || '|database|' || d.datname || '|' || pg_catalog.pg_get_userbyid(d.datdba) || ' ' || COALESCE(d.datacl::text, '')
FROM pg_catalog.pg_database d;
SQL
}

# Local: for the database it runs in, owners and ACLs of relations, columns, routines, types and schemas, and the
# default ACLs.
bms_sql_snapshot_local() {
    cat <<'SQL'
SELECT :'tag' || '|relation|' || n.nspname || '.' || c.relname || '|' || c.relkind::pg_catalog.text || ' ' || pg_catalog.pg_get_userbyid(c.relowner)
       || ' ' || COALESCE(c.relacl::text, '')
FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
UNION ALL
SELECT :'tag' || '|column|' || n.nspname || '.' || c.relname || '.' || a.attname || '|' || a.attacl::text
FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid = a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE a.attacl IS NOT NULL AND NOT a.attisdropped AND n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
UNION ALL
SELECT :'tag' || '|routine|' || f.oid::pg_catalog.regprocedure::text || '|' || pg_catalog.pg_get_userbyid(f.proowner)
       || ' ' || COALESCE(f.proacl::text, '')
FROM pg_catalog.pg_proc f JOIN pg_catalog.pg_namespace n ON n.oid = f.pronamespace
WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
UNION ALL
SELECT :'tag' || '|type|' || n.nspname || '.' || t.typname || '|' || pg_catalog.pg_get_userbyid(t.typowner) || ' ' || COALESCE(t.typacl::text, '')
FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_' AND t.typrelid = 0 AND t.typelem = 0
UNION ALL
SELECT :'tag' || '|schema|' || n.nspname || '|' || pg_catalog.pg_get_userbyid(n.nspowner) || ' ' || COALESCE(n.nspacl::text, '')
FROM pg_catalog.pg_namespace n WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'
UNION ALL
SELECT :'tag' || '|defaultacl|' || pg_catalog.pg_get_userbyid(d.defaclrole) || ' ' || COALESCE(d.defaclnamespace::pg_catalog.regnamespace::text, '*')
       || ' ' || d.defaclobjtype::pg_catalog.text || '|' || d.defaclacl::text
FROM pg_catalog.pg_default_acl d;
SQL
}

# --- Provisioning role: leftovers of a failed attempt ----------------------------------------------------------------

bms_assess_reasons_sql() {
    cat <<'SQL'
WITH p AS (SELECT * FROM pg_catalog.pg_roles WHERE rolname = :'provisioner')
SELECT 'es SUPERUSER, CREATEDB, CREATEROLE, REPLICATION o BYPASSRLS' FROM p
WHERE rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
UNION ALL SELECT 'tiene configuracion propia (ALTER ROLE ... SET)' FROM p WHERE rolconfig IS NOT NULL
UNION ALL SELECT 'tiene limite de conexiones o fecha de caducidad' FROM p WHERE rolconnlimit <> -1 OR rolvaliduntil IS NOT NULL
UNION ALL SELECT 'es miembro de ' || pg_catalog.pg_get_userbyid(m.roleid) || ', que no es un rol de server owner ni el del runtime'
FROM p JOIN pg_catalog.pg_auth_members m ON m.member = p.oid
WHERE pg_catalog.pg_get_userbyid(m.roleid) <> ALL (pg_catalog.string_to_array(:'allowed', ','))
UNION ALL SELECT 'el rol ' || pg_catalog.pg_get_userbyid(m.member) || ' es miembro de el'
FROM p JOIN pg_catalog.pg_auth_members m ON m.roleid = p.oid
UNION ALL SELECT 'tiene ' || count(*) || ' objeto(s) o privilegio(s) fuera de CONNECT en bases (duenos, ACL, politicas)'
FROM p JOIN pg_catalog.pg_shdepend d ON d.refclassid = 'pg_catalog.pg_authid'::pg_catalog.regclass AND d.refobjid = p.oid
WHERE d.deptype <> 'a' OR d.classid <> 'pg_catalog.pg_database'::pg_catalog.regclass
GROUP BY p.oid HAVING count(*) > 0;
SQL
}

# Judges an existing provisioning role [provisioner] for update.sh when server.env does not name it (the rollback of an
# attempt restores server.env but leaves the role, with the memberships and CONNECT grants the attempt gave it).
# Prints one line: "absent", "adoptable clean|touched" (what an attempt leaves: no ownership, no privilege on anything but
# CONNECT on databases, memberships only in the server owner roles and the runtime role, no attribute that gives power)
# or "unexpected: <reason>; <reason>". [owners]: the validated server owner roles, one per line.
bms_provisioner_assess() {
    local runtime=$1 provisioner=$2 owners=$3 exists allowed reasons touched
    { bms_valid_role "$runtime" && bms_valid_role "$provisioner"; } || { printf 'ERROR: rol no valido.\n' >&2; return 1; }
    exists=$(bms_pg_count postgres -v name="$provisioner" <<'SQL'
SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname = :'name';
SQL
) || return 1
    if [[ $exists == 0 ]]; then
        printf 'absent\n'
        return 0
    fi
    allowed=$(printf '%s\n%s\n' "$runtime" "$owners") || return 1
    allowed=$(bms_roles_validated 'roles permitidos' <<< "$allowed") || return 1
    allowed=$(LC_ALL=C sort -u <<< "$allowed" | paste -sd ',' -)
    # The query's own status decides (a pipe to paste would hide a failure when pipefail is off).
    reasons=$(bms_pg_query postgres -v provisioner="$provisioner" -v allowed="$allowed" <<< "$(bms_assess_reasons_sql)") || return 1
    reasons=$(paste -sd ';' - <<< "$reasons")
    if [[ -z $reasons ]]; then
        # "touched": the role already holds what an attempt of update.sh gives it (memberships, CONNECT on databases).
        touched=$(bms_pg_count postgres -v name="$provisioner" <<'SQL'
SELECT (SELECT count(*) FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid)
     + (SELECT count(*) FROM pg_catalog.pg_shdepend d WHERE d.refclassid = 'pg_catalog.pg_authid'::pg_catalog.regclass AND d.refobjid = r.oid)
FROM pg_catalog.pg_roles r WHERE r.rolname = :'name';
SQL
) || return 1
        if [[ $touched == 0 ]]; then printf 'adoptable clean\n'; else printf 'adoptable touched\n'; fi
    else
        printf 'unexpected: %s\n' "$reasons"
    fi
}

# What each registered company database still has to move: "<database> <owner> <pending> <done> <attempted>" per "ok"
# database ([tenants]: "<database> <owner>" lines). pending: objects of public still owned by a superuser or the runtime role
# (what the move would ALTER); done: relations of public the database owner already owns. A database with both
# numbers above 0 is half moved (an attempt that stopped, or tables the server created in an old company). attempted: 1
# when [provisioner] holds an explicit CONNECT on it (an attempt of update.sh got that far).
bms_layout_status() {
    local runtime=$1 provisioner=$2 tenants=$3 database owner script out
    { bms_valid_role "$runtime" && bms_valid_role "$provisioner"; } || { printf 'ERROR: rol no valido.\n' >&2; return 1; }
    while read -r database owner; do
        [[ -n $database ]] || continue
        { bms_valid_tenant_db "$database" && bms_valid_role "$owner"; } || { printf 'ERROR: base o dueno no validos: %q %q.\n' "$database" "$owner" >&2; return 1; }
        script=$({
            bms_psql_set owner "$owner" && bms_psql_set runtime "$runtime" && bms_psql_set provisioner "$provisioner" || exit 1
            printf 'SET search_path = pg_catalog, pg_temp;\nSELECT (SELECT count(*) FROM (\n'
            bms_sql_move_class_select
            printf ') a) + (SELECT count(*) FROM (\n'
            bms_sql_move_proc_select
            printf ') b) + (SELECT count(*) FROM (\n'
            bms_sql_move_type_select
            printf ') c) AS pending,\n'
            printf "(SELECT count(*) FROM pg_catalog.pg_class c WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f') AND pg_catalog.pg_get_userbyid(c.relowner) = :'owner') AS done,\n"
            printf "(SELECT count(*) FROM pg_catalog.pg_database d, pg_catalog.aclexplode(d.datacl) a WHERE d.datname = pg_catalog.current_database() AND a.privilege_type = 'CONNECT' AND a.grantee = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = :'provisioner'))::int AS attempted;\n"
        }) || return 1
        out=$(bms_pg_query "$database" -F ' ' <<< "$script") || return 1
        bms_ascii_match "$out" '^[0-9]+ [0-9]+ [0-9]+$' || { printf 'ERROR: estado de %s ilegible: %q.\n' "$database" "$out" >&2; return 1; }
        printf '%s %s %s\n' "$database" "$owner" "$out"
    done <<< "$tenants"
}

# --- The move ----------------------------------------------------------------------------------------------------------

# One company database, in one transaction: lock_timeout 30 s, then bms_sql_tenant_layout.
bms_migrate_tenant_layout() {
    local database=$1 owner=$2 runtime=$3 script
    { bms_valid_tenant_db "$database" && bms_valid_role "$owner" && bms_valid_role "$runtime"; } || {
        printf 'ERROR: base o rol no validos: %q %q %q.\n' "$database" "$owner" "$runtime" >&2
        return 1
    }
    script=$({
        bms_psql_set owner "$owner" && bms_psql_set runtime "$runtime" || exit 1
        printf "BEGIN;\nSET LOCAL search_path = pg_catalog, pg_temp;\nSET LOCAL lock_timeout = '30s';\n"
        bms_sql_tenant_layout
        printf 'COMMIT;\n'
    }) || return 1
    bms_pg_exec "$database" <<< "$script"
}

# The control database's grants (bms_sql_control_layout), in one transaction.
bms_migrate_control_layout() {
    local control_db=$1 runtime=$2 script
    { bms_valid_control_db "$control_db" && bms_valid_role "$runtime"; } || { printf 'ERROR: base o rol no validos.\n' >&2; return 1; }
    script=$({
        bms_psql_set runtime "$runtime" || exit 1
        printf 'BEGIN;\nSET LOCAL search_path = pg_catalog, pg_temp;\n'
        bms_sql_control_layout
        printf 'COMMIT;\n'
    }) || return 1
    bms_pg_exec "$control_db" <<< "$script"
}

# Runs [sql] (a psql script) in [database] after setting the psql variables name=value pairs that follow.
bms_run_script() {
    local database=$1 body=$2 script pair
    shift 2
    script=$(for pair in "$@"; do bms_psql_set "${pair%%=*}" "${pair#*=}" || exit 1; done; printf '%s\n' "$body") || return 1
    bms_pg_exec "$database" <<< "$script"
}

# Gives the provisioning role [member] SET (never INHERIT) membership in [role] (install.sh).
bms_grant_set_only() {
    local role=$1 member=$2
    { bms_valid_role "$role" && bms_valid_role "$member"; } || { printf 'ERROR: rol no valido: %q %q.\n' "$role" "$member" >&2; return 1; }
    bms_run_script postgres "$(bms_sql_grant_set_only)" role="$role" member="$member"
}

# Moves an existing server to the role layout described at the top of this file. Idempotent; each step leaves the
# service working (grants come before the memberships are revoked). The provisioning role must exist.
# [owners] (optional): the validated server owner roles, one per line, as update.sh recorded them before it changed
# anything; without it they are read now.
# BMS_TEST_FAIL_AT stages: after_provisioner_alter, after_set_memberships, after_connect_control, after_tenant_layout
# (after every company), after_control_layout, after_revoke_membership (after every owner role), after_runtime_alter,
# after_check, after_verify.
bms_migrate_role_layout() {
    local control_db=$1 runtime=$2 provisioner=$3 owners=${4-} tenants database owner role
    if ! bms_valid_role "$runtime" || ! bms_valid_role "$provisioner" || ! bms_valid_control_db "$control_db"; then
        printf 'ERROR: rol o base no valido.\n' >&2
        return 1
    fi
    [[ $runtime != "$provisioner" ]] || { printf 'ERROR: el rol de aprovisionamiento debe ser otro que el del runtime.\n' >&2; return 1; }
    if [[ -z $owners ]]; then owners=$(bms_server_owner_roles "$control_db") || return 1; fi
    owners=$(bms_roles_validated 'roles de server owner' <<< "$owners") || return 1
    [[ -n $owners ]] || { printf 'ERROR: no hay ningun rol de server owner en %s.\n' "$control_db" >&2; return 1; }
    # Everything is decided before anything changes: a registered database that exists but would be left out keeps
    # working only through the runtime role's membership, so the move stops here, with nothing changed.
    local classified rejected
    classified=$(bms_classify_tenant_databases "$control_db") || return 1
    rejected=$(LC_ALL=C awk '$1 == "reject" { $1 = ""; sub(/^ /, ""); print " - " $0 }' <<< "$classified")
    if [[ -n $rejected ]]; then
        printf 'ERROR: estas bases registradas en companies existen pero no cumplen las reglas; no se cambio ningun rol:\n%s\n' "$rejected" >&2
        printf 'Corrige su nombre o su dueno (ALTER DATABASE ... OWNER TO <rol del server owner>) o quita la fila de companies, y vuelve a ejecutar update.sh.\n' >&2
        return 1
    fi
    LC_ALL=C awk '$1 == "missing" { print "AVISO: se omite la base \"" $2 "\": no existe." }' <<< "$classified" >&2
    tenants=$(LC_ALL=C awk '$1 == "ok" { print $2, $3 }' <<< "$classified")

    # The provisioning role: SET membership in the owners' roles and in the runtime role, CONNECT where it works.
    bms_run_script postgres "$(bms_sql_provisioner_role)" provisioner="$provisioner" || return 1
    bms_failpoint after_provisioner_alter || return 1
    while IFS= read -r role; do
        bms_run_script postgres "$(bms_sql_grant_set_only)" role="$role" member="$provisioner" || return 1
    done <<< "$owners"
    bms_run_script postgres "$(bms_sql_grant_set_only)" role="$runtime" member="$provisioner" || return 1
    bms_failpoint after_set_memberships || return 1
    bms_run_script postgres "$(bms_sql_connect_grant)" db="$control_db" member="$provisioner" || return 1
    bms_failpoint after_connect_control || return 1
    while read -r database owner; do
        [[ -n $database ]] || continue
        bms_valid_tenant_db "$database" || { printf 'ERROR: base no valida: %q.\n' "$database" >&2; return 1; }
        bms_run_script postgres "$(bms_sql_connect_grant)" db="$database" member="$provisioner" || return 1
        bms_migrate_tenant_layout "$database" "$owner" "$runtime" || {
            printf 'ERROR: no se pudo pasar %s a la nueva organizacion de roles.\n' "$database" >&2
            return 1
        }
        bms_failpoint after_tenant_layout || return 1
    done <<< "$tenants"

    bms_migrate_control_layout "$control_db" "$runtime" || return 1
    bms_failpoint after_control_layout || return 1

    # Last: the runtime role stops being a member of the owners' roles (every grant of it, whoever granted it) and
    # can never create databases or roles.
    while IFS= read -r role; do
        bms_run_script postgres "$(bms_sql_revoke_membership)" role="$role" member="$runtime" || return 1
        bms_failpoint after_revoke_membership || return 1
    done <<< "$owners"
    bms_run_script postgres "$(bms_sql_runtime_attributes)" runtime="$runtime" || return 1
    bms_failpoint after_runtime_alter || return 1
    bms_check_role_layout "$control_db" "$runtime" "$provisioner" || return 1
    bms_failpoint after_check || return 1
    bms_verify_role_access "$control_db" "$runtime" "$provisioner" "$tenants" || return 1
    bms_failpoint after_verify || return 1
}

# After the move, proof that the service keeps working: as the runtime role (SET ROLE from the superuser, so every
# privilege check is the runtime role's) it can connect to and read application_users in the control database and in
# every company database, and the activity log in each company; the provisioning login can SET ROLE to each
# database's owner — with a real connection when the caller defines bms_pg_as_provisioner <database> (update.sh, with
# the password), otherwise by the catalog. Prints every failure and fails when there is any.
bms_verify_role_access() {
    local control_db=$1 runtime=$2 provisioner=$3 tenants=$4 database owner failures=0 result script
    { bms_valid_role "$runtime" && bms_valid_role "$provisioner"; } || { printf 'ERROR: rol no valido.\n' >&2; return 1; }
    while read -r database owner; do
        [[ -n $database ]] || continue
        if [[ -n $owner ]] && ! { bms_valid_tenant_db "$database" && bms_valid_role "$owner"; }; then
            printf 'ERROR: base o dueno no validos: %q %q.\n' "$database" "$owner" >&2
            failures=$((failures + 1))
            continue
        fi
        script=$({ bms_psql_set runtime "$runtime"; bms_psql_set db "$database"; bms_psql_set tenant "$([[ -n $owner ]] && echo 1 || echo 0)"
                   printf 'SET search_path = pg_catalog, pg_temp;\n'; bms_sql_verify_runtime; }) || return 1
        result=$(bms_pg_query "$database" 2>&1 <<< "$script") ||
            { printf 'ERROR: el rol %s no puede leer %s: %s\n' "$runtime" "$database" "${result//$'\n'/ }" >&2; failures=$((failures + 1)); }
        [[ -n $owner ]] || continue
        if declare -F bms_pg_as_provisioner >/dev/null; then
            result=$(printf 'SET ROLE "%s";\nSELECT current_user;\n' "$owner" | bms_pg_provisioner_query "$database" 2>&1) || result=''
        else
            result=$(bms_pg_query postgres -v member="$provisioner" -v owner="$owner" -v db="$database" 2>&1 <<'SQL'
SELECT CASE WHEN pg_catalog.pg_has_role(:'member', :'owner', 'SET')
             AND pg_catalog.has_database_privilege(:'member', :'db', 'CONNECT') THEN :'owner' END;
SQL
) || result=''
        fi
        if [[ $result != "$owner" ]]; then
            printf 'ERROR: el login de aprovisionamiento %s no puede conectarse a %s y actuar como %s.\n' "$provisioner" "$database" "$owner" >&2
            failures=$((failures + 1))
        fi
    done <<< "$control_db
$tenants"
    (( failures == 0 ))
}

# Fails (with the reason) unless the runtime role holds no membership and no ownership-like attribute.
bms_check_role_layout() {
    local control_db=$1 runtime=$2 provisioner=$3 problems script
    { bms_valid_role "$runtime" && bms_valid_role "$provisioner"; } || { printf 'ERROR: rol no valido.\n' >&2; return 1; }
    script=$({ bms_psql_set runtime "$runtime"; bms_psql_set provisioner "$provisioner"; printf 'SET search_path = pg_catalog, pg_temp;\n'; bms_sql_layout_problems; printf ';\n'; }) || return 1
    problems=$(bms_pg_query postgres <<< "$script") || return 1
    if [[ -n $problems ]]; then
        printf 'ERROR: organizacion de roles incompleta: %s\n' "$problems" >&2
        return 1
    fi
}

# Undo of the last step only (for update.sh's automatic rollback to a release that still connects as the runtime
# role for provisioning): [runtime] is a member of every [owner role] again, with INHERIT and SET (as install.sh made
# it). The roles are the ones update.sh validated and recorded BEFORE it changed anything: nothing is read from the
# database here, so no query can fail the rollback. It goes on after a failure (every role is tried) and prints the
# exact command for each one it could not restore. Grants and owners stay.
bms_restore_runtime_membership() {
    local runtime=$1 role failures=0
    shift
    bms_valid_role "$runtime" || { printf 'ERROR: rol del runtime no valido: %q.\n' "$runtime" >&2; return 1; }
    (( $# > 0 )) || { printf 'ERROR: no hay roles de server owner registrados; no se puede devolver la pertenencia.\n' >&2; return 1; }
    for role in "$@"; do
        if ! bms_valid_role "$role"; then
            printf 'ERROR: rol de server owner no valido, no se toca: %q.\n' "$role" >&2
            failures=$((failures + 1))
            continue
        fi
        if ! { bms_failpoint rollback_grant && bms_run_script postgres "$(bms_sql_restore_membership)" role="$role" member="$runtime"; }; then
            printf 'ERROR: no se pudo devolver %s a %s. A mano: sudo -u postgres psql -c '"'"'GRANT "%s" TO "%s"'"'"'\n' "$runtime" "$role" "$role" "$runtime" >&2
            failures=$((failures + 1))
        fi
    done
    (( failures == 0 ))
}
