#!/usr/bin/env bash
# PostgreSQL role layout of BMS (SELF_HOSTING.md "Roles de PostgreSQL"), sourced by install.sh and update.sh.
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
# The caller defines:
#   bms_pg_super <database> [psql options...]   psql as the PostgreSQL superuser, SQL on stdin, ON_ERROR_STOP on.
# Every query here pins search_path = pg_catalog, pg_temp and schema-qualifies what it touches, and only reads
# catalogs or runs GRANT, REVOKE and ALTER ... OWNER: nothing a company database defines is executed.

# Sourced: the checks here never rely on the caller's locale (bms_ascii_match, LC_ALL=C on sort and the like).
# [[ =~ ]] with LC_ALL=C for this one test, whatever the caller's locale: [a-z], [0-9a-f] or [!-~] mean ASCII.
bms_ascii_match() { local LC_ALL=C; [[ $1 =~ $2 ]]; }

readonly BMS_ROLE_PATTERN='^[a-z][a-z0-9_]{0,62}$'
readonly BMS_TENANT_DB_PATTERN='^bms_[a-z0-9_]{1,59}$'

bms_valid_role() { bms_ascii_match "$1" "$BMS_ROLE_PATTERN" && [[ $1 != pg_* && $1 != postgres ]]; }

# Roles of the active and inactive server owners (application_users.postgres_role of server_owners), one per
# line, only those that exist as PostgreSQL roles and are not superusers.
bms_server_owner_roles() {
    local control_db=$1
    bms_pg_super "$control_db" -At <<'SQL'
SET search_path = pg_catalog, pg_temp;
SELECT DISTINCT r.rolname FROM public.server_owners o
JOIN public.application_users u ON u.id = o.user_id
JOIN pg_catalog.pg_roles r ON r.rolname = u.postgres_role
WHERE NOT r.rolsuper AND r.rolname !~ '^pg_' ORDER BY 1;
SQL
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
    listed=$(bms_pg_super "$control_db" -At -c 'SELECT database_name FROM public.companies ORDER BY code') || return 1
    while IFS= read -r name; do
        [[ -n $name ]] || continue
        exists=$(bms_pg_super postgres -At -v name="$name" <<'SQL'
SELECT count(*) FROM pg_catalog.pg_database WHERE datname = :'name';
SQL
) || return 1
        if [[ $exists == 0 ]]; then
            printf 'missing %s\n' "$name"
            continue
        fi
        if ! bms_ascii_match "$name" "$BMS_TENANT_DB_PATTERN" || [[ $name == "$control_db" ]]; then
            printf 'reject %s no es un nombre de base de empresa (bms_<codigo>) o es la base central\n' "$name"
            continue
        fi
        owner=$(bms_pg_super postgres -At -v name="$name" <<'SQL'
SELECT pg_catalog.pg_get_userbyid(datdba) FROM pg_catalog.pg_database WHERE datname = :'name' AND NOT datistemplate;
SQL
) || return 1
        if [[ -z $owner ]]; then
            printf 'reject %s es una plantilla\n' "$name"
        elif ! grep -Fxq -- "$owner" <<< "$owners"; then
            printf 'reject %s pertenece a %s, que no es el rol de un server owner\n' "$name" "$owner"
        else
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

# Creates the role <name> (LOGIN, the given attributes) or sets its password, with the password read from stdin.
# The verifier only travels inside the SQL on psql's stdin, never on a command line.
bms_role_set_password() {
    local name=$1 attributes=$2 verifier exists
    bms_valid_role "$name" || return 1
    verifier=$(bms_scram_verifier) || return 1
    [[ $verifier == SCRAM-SHA-256\$* && $verifier != *"'"* ]] || return 1
    exists=$(bms_pg_super postgres -At -v name="$name" <<'SQL'
SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname = :'name';
SQL
) || return 1
    if [[ $exists == 0 ]]; then
        printf 'CREATE ROLE "%s" LOGIN %s PASSWORD '"'"'%s'"'"';\n' "$name" "$attributes" "$verifier" | bms_pg_super postgres -q
    else
        printf 'ALTER ROLE "%s" LOGIN %s PASSWORD '"'"'%s'"'"';\n' "$name" "$attributes" "$verifier" | bms_pg_super postgres -q
    fi
}

readonly BMS_PROVISIONER_ATTRIBUTES='NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
readonly BMS_RUNTIME_ATTRIBUTES='NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'

# Gives the provisioning role SET (never INHERIT) membership in [role]. PostgreSQL 16+ records the options per
# grant; older versions take them from the member's NOINHERIT attribute.
bms_grant_set_only() {
    local role=$1 member=$2
    bms_pg_super postgres -q -v role="$role" -v member="$member" <<'SQL'
SET search_path = pg_catalog, pg_temp;
SELECT pg_catalog.current_setting('server_version_num')::int >= 160000 AS pg16 \gset
\if :pg16
GRANT :"role" TO :"member" WITH INHERIT FALSE, SET TRUE;
\else
GRANT :"role" TO :"member";
\endif
SQL
}

# Grants the runtime role, explicitly, every DML privilege it holds today on the objects of one company database
# (until now partly through its membership in the owner's role), then gives what an older update.sh created as the
# superuser (or what the runtime role itself owns) to the database owner, and leaves the activity log exactly
# SELECT, INSERT (USAGE on its sequence) for the runtime role. One transaction; running it again changes nothing.
bms_migrate_tenant_layout() {
    local database=$1 owner=$2 runtime=$3
    bms_pg_super "$database" -q -v owner="$owner" -v runtime="$runtime" <<'SQL'
BEGIN;
SET LOCAL search_path = pg_catalog, pg_temp;
SET LOCAL lock_timeout = '30s';
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
-- 2. Objects of schema public owned by a superuser (an older update.sh) or by the runtime role go to the database
-- owner, so later migrations run as that role. Objects of extensions and sequences that belong to a column move
-- with their extension or table. Every object of the activity log moves together (its owners stay one role).
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
\gexec
SELECT pg_catalog.format('ALTER %s %s OWNER TO %I', CASE f.prokind WHEN 'a' THEN 'AGGREGATE' ELSE 'ROUTINE' END,
           f.oid::pg_catalog.regprocedure, :'owner')
FROM pg_catalog.pg_proc f
JOIN pg_catalog.pg_namespace n ON n.oid = f.pronamespace
JOIN pg_catalog.pg_roles r ON r.oid = f.proowner
WHERE n.nspname = 'public' AND (r.rolsuper OR r.rolname = :'runtime')
  AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
                  WHERE d.classid = 'pg_catalog.pg_proc'::pg_catalog.regclass AND d.objid = f.oid AND d.deptype = 'e')
ORDER BY f.oid
\gexec
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
\gexec
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
COMMIT;
SQL
}

# Moves an existing server to the role layout described at the top of this file. Idempotent; each step leaves the
# service working (grants come before the memberships are revoked). The provisioning role must exist.
bms_migrate_role_layout() {
    local control_db=$1 runtime=$2 provisioner=$3 owners tenants database owner role
    if ! bms_valid_role "$runtime" || ! bms_valid_role "$provisioner"; then
        printf 'ERROR: rol no valido.\n' >&2
        return 1
    fi
    [[ $runtime != "$provisioner" ]] || { printf 'ERROR: el rol de aprovisionamiento debe ser otro que el del runtime.\n' >&2; return 1; }
    owners=$(bms_server_owner_roles "$control_db") || return 1
    [[ -n $owners ]] || { printf 'ERROR: no hay ningun rol de server owner en %s.\n' "$control_db" >&2; return 1; }
    # Everything is decided before anything changes: a registered database that exists but would be left out keeps
    # working only through the runtime role's membership, so the move stops here, with nothing changed.
    local classified rejected
    classified=$(bms_classify_tenant_databases "$control_db") || return 1
    rejected=$(awk '$1 == "reject" { $1 = ""; sub(/^ /, ""); print " - " $0 }' <<< "$classified")
    if [[ -n $rejected ]]; then
        printf 'ERROR: estas bases registradas en companies existen pero no cumplen las reglas; no se cambio ningun rol:\n%s\n' "$rejected" >&2
        printf 'Corrige su nombre o su dueno (ALTER DATABASE ... OWNER TO <rol del server owner>) o quita la fila de companies, y vuelve a ejecutar update.sh.\n' >&2
        return 1
    fi
    awk '$1 == "missing" { print "AVISO: se omite la base \"" $2 "\": no existe." }' <<< "$classified" >&2
    tenants=$(awk '$1 == "ok" { print $2, $3 }' <<< "$classified")

    # The provisioning role: SET membership in the owners' roles and in the runtime role, CONNECT where it works.
    printf 'ALTER ROLE "%s" %s;\n' "$provisioner" "$BMS_PROVISIONER_ATTRIBUTES" | bms_pg_super postgres -q || return 1
    while IFS= read -r role; do
        [[ -n $role ]] || continue
        bms_grant_set_only "$role" "$provisioner" || return 1
    done <<< "$owners"
    bms_grant_set_only "$runtime" "$provisioner" || return 1
    bms_pg_super postgres -q -v db="$control_db" -v member="$provisioner" <<'SQL' || return 1
GRANT CONNECT ON DATABASE :"db" TO :"member";
SQL
    while read -r database owner; do
        [[ -n $database ]] || continue
        bms_pg_super postgres -q -v db="$database" -v member="$provisioner" <<'SQL' || return 1
GRANT CONNECT ON DATABASE :"db" TO :"member";
SQL
        bms_migrate_tenant_layout "$database" "$owner" "$runtime" || {
            printf 'ERROR: no se pudo pasar %s a la nueva organizacion de roles.\n' "$database" >&2
            return 1
        }
    done <<< "$tenants"

    # Control database: everything there belongs to the superuser; the runtime keeps its explicit grants, and the
    # server audit and the data key of control V017 stay SELECT, INSERT for it.
    bms_pg_super "$control_db" -q -v runtime="$runtime" <<'SQL' || return 1
BEGIN;
SET LOCAL search_path = pg_catalog, pg_temp;
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
COMMIT;
SQL

    # Last: the runtime role stops being a member of the owners' roles (every grant of it, whoever granted it) and
    # can never create databases or roles.
    while IFS= read -r role; do
        [[ -n $role ]] || continue
        bms_pg_super postgres -q -v role="$role" -v member="$runtime" <<'SQL' || return 1
SET search_path = pg_catalog, pg_temp;
SELECT pg_catalog.format('REVOKE %I FROM %I GRANTED BY %I', :'role', :'member', pg_catalog.pg_get_userbyid(m.grantor))
FROM pg_catalog.pg_auth_members m
WHERE m.roleid = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = :'role')
  AND m.member = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = :'member')
\gexec
SQL
    done <<< "$owners"
    printf 'ALTER ROLE "%s" %s;\n' "$runtime" "$BMS_RUNTIME_ATTRIBUTES" | bms_pg_super postgres -q || return 1
    bms_check_role_layout "$control_db" "$runtime" "$provisioner" || return 1
    bms_verify_role_access "$control_db" "$runtime" "$provisioner" "$tenants"
}

# After the move, proof that the service keeps working: as the runtime role (SET ROLE from the superuser, so every
# privilege check is the runtime role's) it can connect to and read application_users in the control database and in
# every company database, and the activity log in each company; the provisioning login can SET ROLE to each
# database's owner — with a real connection when the caller defines bms_pg_as_provisioner <database> (update.sh, with
# the password), otherwise by the catalog. Prints every failure and fails when there is any.
bms_verify_role_access() {
    local control_db=$1 runtime=$2 provisioner=$3 tenants=$4 database owner failures=0 result
    while read -r database owner; do
        [[ -n $database ]] || continue
        result=$(bms_pg_super "$database" -At -v runtime="$runtime" -v db="$database" -v tenant="$([[ -n $owner ]] && echo 1 || echo 0)" 2>&1 <<'SQL'
SET search_path = pg_catalog, pg_temp;
SELECT pg_catalog.has_database_privilege(:'runtime', :'db', 'CONNECT') AS can_connect \gset
\if :can_connect
\else
SELECT 1 / 0 AS sin_connect;
\endif
SET ROLE :"runtime";
SELECT count(*) >= 0 FROM public.application_users;
\if :tenant
SELECT count(*) >= 0 FROM (SELECT 1 FROM public.activity_log LIMIT 1) AS l;
SELECT count(*) >= 0 FROM (SELECT 1 FROM public.activity_log_chain LIMIT 1) AS c;
\endif
RESET ROLE;
SQL
) || { printf 'ERROR: el rol %s no puede leer %s: %s\n' "$runtime" "$database" "${result//$'\n'/ }" >&2; failures=$((failures + 1)); }
        [[ -n $owner ]] || continue
        if declare -F bms_pg_as_provisioner >/dev/null; then
            result=$(printf 'SET ROLE "%s";\nSELECT current_user;\n' "$owner" | bms_pg_as_provisioner "$database" -At 2>&1 | tail -n 1) || result=''
        else
            result=$(bms_pg_super postgres -At -v member="$provisioner" -v owner="$owner" -v db="$database" 2>&1 <<'SQL'
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
    local control_db=$1 runtime=$2 provisioner=$3 problems
    problems=$(bms_pg_super postgres -At -v runtime="$runtime" -v provisioner="$provisioner" <<'SQL'
SET search_path = pg_catalog, pg_temp;
SELECT 'el rol ' || :'runtime' || ' es miembro de ' || r.rolname || ', que crea bases o roles o es dueno de una base'
FROM pg_catalog.pg_auth_members m JOIN pg_catalog.pg_roles r ON r.oid = m.roleid
WHERE m.member = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = :'runtime')
  AND (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR EXISTS (SELECT 1 FROM pg_catalog.pg_database d WHERE d.datdba = r.oid))
UNION ALL
SELECT 'el rol ' || rolname || ' tiene SUPERUSER, CREATEDB o CREATEROLE' FROM pg_catalog.pg_roles
WHERE rolname IN (:'runtime', :'provisioner') AND (rolsuper OR rolcreatedb OR rolcreaterole)
UNION ALL
SELECT 'el rol ' || rolname || ' hereda privilegios (INHERIT)' FROM pg_catalog.pg_roles
WHERE rolname = :'provisioner' AND rolinherit;
SQL
) || return 1
    if [[ -n $problems ]]; then
        printf 'ERROR: organizacion de roles incompleta: %s\n' "$problems" >&2
        return 1
    fi
}

# Undo of the last step only (for update.sh's automatic rollback to a release that still connects as the runtime
# role for provisioning): the runtime role is a member of the owners' roles again. Grants and owners stay.
bms_restore_runtime_membership() {
    local control_db=$1 runtime=$2 role
    while IFS= read -r role; do
        [[ -n $role ]] || continue
        bms_pg_super postgres -q -v role="$role" -v member="$runtime" <<'SQL' || return 1
GRANT :"role" TO :"member";
SQL
    done < <(bms_server_owner_roles "$control_db")
}
