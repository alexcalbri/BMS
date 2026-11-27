#!/usr/bin/env bash
# Regression test of the root scripts against the server owner's PostgreSQL role (ACTIVITY_LOG.md §9,
# SELF_HOSTING.md): that role (CREATEDB CREATEROLE, the runtime role is a member of it) owns every company
# database and its schema public, and here it prepares everything it can before activity-harden.sh and the
# update.sh migrations run as root:
#   - functions in public shadowing the catalog (set_config, format, quote_ident, pg_has_role, aclexplode,
#     to_regclass, current_setting...) and an operator, each making the owner's role SUPERUSER when called;
#   - per-database settings: search_path = public, pg_catalog; role = <owner>; default_transaction_read_only;
#   - tampering of the log itself: extra triggers (one making the role superuser), a DO INSTEAD rule, row
#     security and policies, defaults, an extra column, inheritance, an UNLOGGED head, constraints and an
#     index whose functions make the role superuser, a function overload, a forged hardening date, unsealed rows;
#   - a trigger on schema_migrations making the role superuser (for the company migrations of update.sh).
# It checks that the role never becomes superuser, that hardening completes and leaves exactly V011, that
# what was removed is in the log and the server audit, that a hardened company is only read (no lock that
# blocks writers), that a busy one is retried, and that all control and tenant migrations still apply through
# the same helpers.
#
# Run as root on a DISPOSABLE PostgreSQL 16 whose superuser postgres is reachable over TCP without a password:
#   BMS_PG_HOST=127.0.0.1 BMS_PG_PORT=54329 bash scripts/ubuntu/tests/activity-harden-test.sh
# It creates and drops databases and roles named fix_ht_* (and creates, never drops, the role bms_audit).
set -Eeuo pipefail

[[ $EUID -eq 0 ]] || { echo 'Debe ejecutarse como root (runuser -u postgres).' >&2; exit 2; }
export BMS_PG_HOST=${BMS_PG_HOST:-127.0.0.1} BMS_PG_PORT=${BMS_PG_PORT:-5432}
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd -- "$here/../../.." && pwd)
work=$(mktemp -d)
owner=fix_ht_owner
runtime=fix_ht_rt
central=fix_ht_central
failures=0

su_psql() { psql -X -q -v ON_ERROR_STOP=1 -h "$BMS_PG_HOST" -p "$BMS_PG_PORT" -U postgres "$@"; }
rt_psql() { PGPASSWORD=rt-pass psql -X -q -v ON_ERROR_STOP=1 -h "$BMS_PG_HOST" -p "$BMS_PG_PORT" -U "$runtime" "$@"; }
# As the server owner's role, the way a compromised runtime gets it.
as_owner() { local db=$1; shift; { echo "SET ROLE $owner;"; cat; } | rt_psql -d "$db" "$@"; }
check() {
    local what=$1
    shift
    if "$@"; then printf 'ok   %s\n' "$what"; else printf 'FAIL %s\n' "$what"; failures=$((failures + 1)); fi
}
owner_not_superuser() { [[ $(su_psql -d postgres -Atc "SELECT rolsuper FROM pg_roles WHERE rolname = '$owner'") == f ]]; }

cleanup() {
    su_psql -d postgres -At -c "SELECT datname FROM pg_database WHERE datname LIKE 'fix\_ht\_%'" 2>/dev/null |
        while read -r db; do su_psql -d postgres -c "DROP DATABASE IF EXISTS \"$db\" WITH (FORCE)" 2>/dev/null || true; done
    su_psql -d postgres -c "DROP ROLE IF EXISTS $runtime" -c "DROP ROLE IF EXISTS $owner" 2>/dev/null || true
    rm -rf -- "$work"
}
trap cleanup EXIT
cleanup
work=$(mktemp -d)

# --- Roles and the control database as install.sh leaves them -----------------------------------------
su_psql -d postgres <<SQL
CREATE ROLE $owner LOGIN NOSUPERUSER CREATEDB CREATEROLE PASSWORD 'owner-pass';
CREATE ROLE $runtime LOGIN PASSWORD 'rt-pass';
GRANT $owner TO $runtime;
CREATE DATABASE $central;
REVOKE ALL ON DATABASE $central FROM PUBLIC;
GRANT CONNECT ON DATABASE $central TO $runtime, $owner;
SQL
printf 'DB_URL=jdbc:postgresql://%s:%s/%s\nDB_USER=%s\nDB_PASSWORD="rt-pass"\n' "$BMS_PG_HOST" "$BMS_PG_PORT" "$central" "$runtime" > "$work/server.env"
chmod 600 "$work/server.env"
export BMS_SERVER_ENV=$work/server.env BMS_ACTIVITY_HARDEN_LOG=$work/harden.log BMS_ACTIVITY_HARDEN_LOCK=$work/harden.lock

# All core and control migrations through the helpers update.sh/install.sh use (pg_control, public first).
# shellcheck source=scripts/ubuntu/migrations.sh
source "$repo/scripts/ubuntu/migrations.sh"
log() { printf '%s\n' "$*" >> "$work/helpers.log"; }
control_ok=true
for file in "$repo"/database/core/migrations/V00{1,2,3,4,6}*.sql "$repo"/database/control/migrations/*.sql; do
    module=control
    [[ $file == */core/* ]] && module=core
    apply_migration "$central" "$module" "$file" >/dev/null 2>>"$work/helpers.log" || control_ok=false
done
check 'todas las migraciones core y control se aplican con pg_control' $control_ok
pg_control "$central" -v runtime="$runtime" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"runtime";
GRANT SELECT, INSERT, UPDATE, DELETE ON companies TO :"runtime";
GRANT SELECT, INSERT ON provisioning_audit_log TO :"runtime";
SQL

# --- Company databases as the server creates them: by the owner's role, migrated as that role -------------
tenant_migrations=(
    database/core/migrations/V001__create_application_users.sql:core
    database/core/migrations/V002__create_application_sessions.sql:core
    database/tenant/migrations/V001__create_business_owner.sql:tenant
    database/tenant/migrations/V003__create_tenant_modules.sql:tenant
    database/tenant/migrations/V004__create_application_permissions.sql:tenant
    database/tenant/migrations/V005__create_application_user_credentials.sql:tenant
    database/core/migrations/V003__add_application_user_profile.sql:core
    database/core/migrations/V005__create_notifications.sql:core
    database/core/migrations/V006__add_application_user_language.sql:core
    database/tenant/migrations/V006__create_extensions.sql:tenant
    database/tenant/migrations/V007__create_company_mail_settings.sql:tenant
    database/tenant/migrations/V008__account_reset_throttle.sql:tenant
    database/tenant/migrations/V009__outbound.sql:tenant
    database/tenant/migrations/V010__create_text_translations.sql:tenant
    database/tenant/migrations/V011__create_activity_log.sql:tenant
)
pg_owner_prepare "$BMS_SERVER_ENV"
owner_runner() { local db=$1; shift; pg_owner "$db" "$owner" "$@"; }
new_company() {
    local db=$1 code=$2 with_log=${3:-yes} entry
    rt_psql -d postgres -c "SET ROLE $owner" -c "CREATE DATABASE $db" -c "REVOKE CONNECT ON DATABASE $db FROM PUBLIC" -c "GRANT CONNECT ON DATABASE $db TO $runtime"
    for entry in "${tenant_migrations[@]}"; do
        [[ $with_log == yes || $entry != *V011* ]] || continue
        MIGRATION_RUNNER=owner_runner apply_migration "$db" "${entry##*:}" "$repo/${entry%%:*}" >/dev/null 2>>"$work/helpers.log" || return 1
    done
    su_psql -d "$central" -c "INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), '$code', 'Empresa $code', '$db', gen_random_uuid())"
}
write_rows() {
    local db=$1 count=$2 summary=${3:-Fila}
    rt_psql -d "$db" -c "INSERT INTO public.activity_log (actor_kind, actor_name, actor_display, module_id, entity_type, action, summary)
        SELECT 'user', 'Ana', 'Ana', 'crm', 'lead', 'created', '$summary ' || g FROM generate_series(1, $count) g"
}
tenants_ok=true
for spec in fix_ht_a:a fix_ht_b:b fix_ht_c:c; do
    new_company "${spec%%:*}" "${spec##*:}" || tenants_ok=false
    write_rows "${spec%%:*}" 3
done
new_company fix_ht_d d no || tenants_ok=false
su_psql -d "$central" -c "INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), 'gone', 'Borrada', 'fix_ht_missing', gen_random_uuid())"
check 'todas las migraciones de empresa se aplican como el rol del dueno (pg_owner)' $tenants_ok

# --- C1: the catalog shadowed in public, and per-database settings ---------------------------------------
escalate="BEGIN BEGIN EXECUTE 'ALTER ROLE $owner SUPERUSER'; EXCEPTION WHEN OTHERS THEN NULL; END;"
as_owner fix_ht_a <<SQL
CREATE FUNCTION public.set_config(text, text, boolean) RETURNS text LANGUAGE plpgsql AS \$\$ $escalate RETURN ''; END \$\$;
CREATE FUNCTION public.format(text, text) RETURNS text LANGUAGE plpgsql AS \$\$ $escalate RETURN ''; END \$\$;
CREATE FUNCTION public.format(text, regclass) RETURNS text LANGUAGE plpgsql AS \$\$ $escalate RETURN ''; END \$\$;
CREATE FUNCTION public.quote_ident(text) RETURNS text LANGUAGE plpgsql AS \$\$ $escalate RETURN ''; END \$\$;
CREATE FUNCTION public.pg_has_role(name, name, text) RETURNS boolean LANGUAGE plpgsql AS \$\$ $escalate RETURN false; END \$\$;
CREATE FUNCTION public.pg_has_role(text, text, text) RETURNS boolean LANGUAGE plpgsql AS \$\$ $escalate RETURN false; END \$\$;
CREATE FUNCTION public.to_regclass(text) RETURNS regclass LANGUAGE plpgsql AS \$\$ $escalate RETURN NULL; END \$\$;
CREATE FUNCTION public.current_setting(text) RETURNS text LANGUAGE plpgsql AS \$\$ $escalate RETURN ''; END \$\$;
CREATE FUNCTION public.current_setting(text, boolean) RETURNS text LANGUAGE plpgsql AS \$\$ $escalate RETURN ''; END \$\$;
CREATE FUNCTION public.pg_advisory_xact_lock(bigint) RETURNS void LANGUAGE plpgsql AS \$\$ $escalate END \$\$;
CREATE FUNCTION public.pg_get_userbyid(oid) RETURNS name LANGUAGE plpgsql AS \$\$ $escalate RETURN ''; END \$\$;
CREATE FUNCTION public.evil_eq(text, text) RETURNS boolean LANGUAGE plpgsql AS \$\$ $escalate RETURN true; END \$\$;
CREATE OPERATOR public.= (LEFTARG = text, RIGHTARG = text, FUNCTION = public.evil_eq);
CREATE FUNCTION public.evil_ne(name, text) RETURNS boolean LANGUAGE plpgsql AS \$\$ $escalate RETURN true; END \$\$;
CREATE OPERATOR public.<> (LEFTARG = name, RIGHTARG = text, FUNCTION = public.evil_ne);
ALTER DATABASE fix_ht_a SET search_path = public, pg_catalog;
SQL
as_owner fix_ht_b <<SQL
ALTER DATABASE fix_ht_b SET role = '$owner';
ALTER DATABASE fix_ht_b SET default_transaction_read_only = on;
ALTER DATABASE fix_ht_b SET statement_timeout = '1ms';
SQL

# --- H1: the log tampered before its first hardening ----------------------------------------------------
as_owner fix_ht_c <<SQL
CREATE FUNCTION public.evil_trigger() RETURNS trigger LANGUAGE plpgsql AS \$\$ $escalate NEW.summary := 'Reescrita.'; RETURN NEW; END \$\$;
CREATE FUNCTION public.evil_check(bigint) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS \$\$ $escalate RETURN true; END \$\$;
CREATE TRIGGER zz_rewrite BEFORE INSERT ON activity_log FOR EACH ROW EXECUTE FUNCTION public.evil_trigger();
CREATE TRIGGER zz_chain BEFORE INSERT ON activity_log_chain FOR EACH ROW EXECUTE FUNCTION public.evil_trigger();
CREATE RULE swallow AS ON INSERT TO activity_log WHERE NEW.summary LIKE 'tragar%' DO INSTEAD NOTHING;
ALTER TABLE activity_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY hide ON activity_log FOR SELECT USING (summary <> 'oculta');
ALTER TABLE activity_log ALTER COLUMN entity_label SET DEFAULT 'x';
ALTER TABLE activity_log ADD COLUMN extra text DEFAULT 'oculto';
CREATE TABLE activity_log_shadow () INHERITS (activity_log);
ALTER TABLE activity_log_chain ADD CONSTRAINT evil CHECK (public.evil_check(id));
CREATE INDEX evil_idx ON activity_log_chain ((public.evil_check(id)));
ALTER TABLE activity_log_chain_head SET UNLOGGED;
CREATE FUNCTION public.activity_log_seal(integer) RETURNS integer LANGUAGE sql AS 'SELECT 1';
UPDATE activity_log_chain_head SET hardened_at = '2000-01-01', hardened_seq = 0;
-- Rows left without their seal: hardening seals them (and says they are before the guarantee).
ALTER TABLE activity_log DISABLE TRIGGER activity_log_seal;
INSERT INTO activity_log (actor_kind, actor_name, actor_display, module_id, entity_type, action, summary) VALUES ('user', 'x', 'x', 'crm', 'x', 'created', 'sin sello');
-- A trigger on schema_migrations for the migrations update.sh runs.
CREATE FUNCTION public.evil_any_row() RETURNS trigger LANGUAGE plpgsql AS \$\$ $escalate RETURN NEW; END \$\$;
CREATE TRIGGER zz_migrations BEFORE INSERT ON schema_migrations FOR EACH ROW EXECUTE FUNCTION public.evil_any_row();
SQL
check 'el rol del dueno no es superusuario antes de empezar' owner_not_superuser

# --- Hardening --------------------------------------------------------------------------------------------
harden() { bash "$repo/scripts/ubuntu/activity-harden.sh" "$@"; }
status=0
harden >"$work/run1.out" 2>&1 || status=$?
check 'la proteccion termina sin errores' test "$status" -eq 0
check 'C1/H1: el rol del dueno NO paso a ser superusuario' owner_not_superuser
check 'C1: el ajuste search_path de la base se registro y se quito' grep -q 'fix_ht_a tiene el ajuste "search_path=public, pg_catalog"' "$work/harden.log"
check 'C1: el ajuste role de la base se registro y se quito' grep -q "fix_ht_b tiene el ajuste \"role=$owner\"" "$work/harden.log"
no_settings() { [[ -z $(su_psql -d postgres -Atc "SELECT setconfig FROM pg_db_role_setting WHERE setdatabase IN (SELECT oid FROM pg_database WHERE datname LIKE 'fix\_ht\_%')") ]]; }
check 'C1: no queda ningun ajuste por base' no_settings
check 'H1: el disparador de mas quedo en el registro' grep -q 'zz_rewrite de activity_log no es de V011' "$work/harden.log"
check 'H1: la regla de mas quedo en el registro' grep -q 'removed rule swallow on activity_log' "$work/harden.log"
check 'H1: la columna de mas quedo en el registro' grep -q 'removed column activity_log.extra' "$work/harden.log"
check 'H1: la herencia quedo en el registro' grep -q 'removed inheritance' "$work/harden.log"
check 'H1: las filas sin sello se sellaron y se dijo' grep -q 'sealed 1 rows written without their seal' "$work/harden.log"
check 'una base inexistente se registra' grep -q 'fix_ht_missing de la empresa gone no existe' "$work/harden.log"
check 'una empresa sin V011 se omite' grep -q 'fix_ht_d: aun sin el registro' "$work/harden.log"
for db in fix_ht_a fix_ht_b fix_ht_c; do
    check "$db queda protegida" grep -q "OK $db (protegida" "$work/harden.log"
done
evidence_rows() { [[ $(su_psql -d "$central" -Atc "SELECT count(DISTINCT details->>'database') FROM provisioning_audit_log WHERE action = 'activity.integrity_failed' AND details->>'source' = 'activity-harden' AND details->>'database' IN ('fix_ht_a', 'fix_ht_b', 'fix_ht_c')") == 3 ]]; }
check 'la evidencia de a, b y c esta en provisioning_audit_log' evidence_rows
c_clean() {
    [[ $(su_psql -d fix_ht_c -Atc "SELECT count(*) FROM pg_trigger WHERE tgrelid IN ('public.activity_log'::regclass, 'public.activity_log_chain'::regclass) AND NOT tgisinternal") == 6 &&
       $(su_psql -d fix_ht_c -Atc "SELECT count(*) FROM pg_rewrite WHERE ev_class = 'public.activity_log'::regclass") == 0 &&
       $(su_psql -d fix_ht_c -Atc "SELECT count(*) FROM pg_policy") == 0 &&
       $(su_psql -d fix_ht_c -Atc "SELECT count(*) FROM pg_inherits") == 0 &&
       $(su_psql -d fix_ht_c -Atc "SELECT relpersistence FROM pg_class WHERE oid = 'public.activity_log_chain_head'::regclass") == p &&
       $(su_psql -d fix_ht_c -Atc "SELECT hardened_at > now() - interval '1 hour' AND hardened_seq = 4 FROM activity_log_chain_head") == t ]]
}
check 'H1: activity_log, la cadena y la cabeza quedan exactamente como V011 (y la garantia empieza ahora, tras 4 filas)' c_clean
write_rows fix_ht_c 2 tragar
honest() { [[ $(su_psql -d fix_ht_c -Atc "SELECT count(*) FILTER (WHERE summary LIKE 'tragar%'), (SELECT count(*) FROM activity_log_chain), (SELECT count(*) FROM activity_log) FROM activity_log") == '2|6|6' ]]; }
check 'H1: las filas nuevas se guardan tal cual y se sellan' honest
check 'H1: el rol del dueno sigue sin ser superusuario tras escribir' owner_not_superuser

# --- M1: a hardened company is only read ------------------------------------------------------------------
: > "$work/harden.log"
{ echo "BEGIN; INSERT INTO public.activity_log (actor_kind, actor_name, actor_display, module_id, entity_type, action, summary) VALUES ('user','a','a','crm','x','created','abierta'); SELECT pg_sleep(20); ROLLBACK;" | rt_psql -d fix_ht_c >/dev/null 2>&1 & }
writer=$!
sleep 1
started=$SECONDS
harden >"$work/run2.out" 2>&1 || true
check 'M1: una empresa ya protegida no se toca (ni espera a quien escribe)' test $((SECONDS - started)) -lt 10
check 'M1: las tres dicen "ya protegida"' test "$(grep -c 'ya protegida desde' "$work/harden.log")" -eq 3
# Something to do on a busy company: the lock is not obtained in 3 s, retried next time.
su_psql -d fix_ht_c -c "GRANT UPDATE ON public.activity_log TO PUBLIC"
: > "$work/harden.log"
started=$SECONDS
harden --database fix_ht_c >"$work/run3.out" 2>&1 || true
check 'M1: una empresa ocupada se reintenta despues (lock_timeout 3 s)' grep -q 'fix_ht_c: ocupada' "$work/harden.log"
check 'M1: sin esperar a quien escribe' test $((SECONDS - started)) -lt 12
wait "$writer" || true
: > "$work/harden.log"
harden --database fix_ht_c >"$work/run4.out" 2>&1 || true
check 'M1: libre, se vuelve a dejar exacta' grep -q 'OK fix_ht_c (protegida' "$work/harden.log"

# --- update.sh: company migrations as the owner's role, control only when it belongs to the superuser -----
cat > "$work/V900__fix_test.sql" <<'SQL'
BEGIN;
CREATE TABLE IF NOT EXISTS fix_ht_table (id INT);
COMMIT;
SQL
MIGRATION_RUNNER=owner_runner apply_migration fix_ht_c tenant "$work/V900__fix_test.sql" >/dev/null
check 'una migracion de empresa con un disparador hostil en schema_migrations no da superusuario' owner_not_superuser
recorded() { [[ $(su_psql -d fix_ht_c -Atc "SELECT count(*) FROM schema_migrations WHERE version = 'V900__fix_test'") == 1 ]]; }
check 'y se registra (como el rol del dueno)' recorded
su_psql -d "$central" -c "CREATE FUNCTION public.fix_ht_mine() RETURNS int LANGUAGE sql AS 'SELECT 1'" -c "ALTER FUNCTION public.fix_ht_mine() OWNER TO $owner"
refused() { ! pg_control "$central" -Atc 'SELECT 1' >/dev/null 2>&1; }
check 'pg_control rechaza una base central con objetos de otro rol en public' refused
pg_owner_cleanup

# Control experiment: the same database driven the old way (a plain superuser psql obeying its settings) does
# hand the owner's role SUPERUSER, so the scenario above is the real attack.
as_owner fix_ht_a <<< "ALTER DATABASE fix_ht_a SET search_path = public, pg_catalog;"
su_psql -d fix_ht_a -Atc "SELECT set_config('bms.runtime_user', 'x', false)" >/dev/null
escalated() { [[ $(su_psql -d postgres -Atc "SELECT rolsuper FROM pg_roles WHERE rolname = '$owner'") == t ]]; }
check 'control: sin pg-safe.sh el rol del dueno SI pasa a superusuario' escalated
su_psql -d postgres -c "ALTER ROLE $owner NOSUPERUSER"

printf '\n%s\n' "$( ((failures == 0)) && echo 'TODO OK' || echo "$failures FALLOS")"
((failures == 0))
