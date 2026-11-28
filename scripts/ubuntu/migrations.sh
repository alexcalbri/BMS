#!/usr/bin/env bash
# Sourced by install.sh and update.sh (always from the root-owned tree, never from /opt/bms/source).
# Migration files must contain one outer BEGIN/COMMIT and transactional SQL.
#
# Who runs a migration: by default the PostgreSQL superuser (the control database, which belongs to it). With
# MIGRATION_ROLE set (company databases, update.sh), the provisioning login (MIGRATION_LOGIN, password in the root-only
# MIGRATION_PASSFILE, TCP to MIGRATION_HOST:MIGRATION_PORT) runs it after SET ROLE to the database owner, with a fixed
# search_path (public; pg_catalog is searched first): nothing in a company database runs with superuser rights.

# Sourced: the checks here never rely on the caller's locale.
# [[ =~ ]] with LC_ALL=C for this one test, whatever the caller's locale: [a-z], [0-9a-f] or [!-~] mean ASCII.
bms_ascii_match() { local LC_ALL=C; [[ $1 =~ $2 ]]; }

migration_psql() {
    local target=$1
    shift
    if [[ -z ${MIGRATION_ROLE:-} ]]; then
        runuser -u postgres -- psql -X -d "$target" "$@"
        return
    fi
    { bms_ascii_match "$MIGRATION_ROLE" '^[a-z][a-z0-9_]{0,62}$' && bms_ascii_match "${MIGRATION_LOGIN:-}" '^[a-z][a-z0-9_]{0,62}$'; } || {
        printf 'ERROR: MIGRATION_ROLE o MIGRATION_LOGIN invalido.\n' >&2
        return 1
    }
    [[ -n ${MIGRATION_PASSFILE:-} && -f $MIGRATION_PASSFILE ]] || {
        printf 'ERROR: falta MIGRATION_PASSFILE.\n' >&2
        return 1
    }
    # The settings travel in the startup packet, so they win over anything the database or a role sets for itself.
    {
        printf 'SET ROLE "%s";\n' "$MIGRATION_ROLE"
        cat
    } | PGPASSFILE=$MIGRATION_PASSFILE PGOPTIONS='-c search_path=public -c statement_timeout=0' PGCONNECT_TIMEOUT=15 \
        psql -X -w -h "${MIGRATION_HOST:-127.0.0.1}" -p "${MIGRATION_PORT:-5432}" -U "$MIGRATION_LOGIN" -d "$target" "$@"
}

# The psql script of one migration, WITHOUT the outer BEGIN and COMMIT: the history check, the migration's own SQL
# (its outer BEGIN/COMMIT removed) and the history row. The psql variables module, version, checksum and
# trimmed_checksum are set at its top with \set. apply_migration wraps it in a transaction of its own; the rehearsal
# (rehearse.sh) wraps many of them in one transaction that it rolls back.
bms_migration_script() {
    local module=$1 file=$2 version checksum trimmed_checksum body
    bms_ascii_match "$module" '^[A-Za-z0-9_-]{1,64}$' || { printf 'ERROR: modulo de migracion no valido: %q\n' "$module" >&2; return 1; }
    version=$(basename "$file" .sql)
    bms_ascii_match "$version" '^[A-Za-z0-9_.-]{1,200}$' || { printf 'ERROR: nombre de migracion no valido: %q\n' "$version" >&2; return 1; }
    checksum=$(sha256sum "$file") || return 1
    checksum=${checksum%% *}
    # Earlier servers recorded the hash of the trimmed text; both mean the migration is unchanged.
    trimmed_checksum=$(python3 -c 'import hashlib, pathlib, sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").strip().encode()).hexdigest())' "$file") || return 1
    # Stop on invalid files so psql cannot commit partial work.
    body=$(python3 - "$file" <<'PY'
import pathlib, sys
sql = pathlib.Path(sys.argv[1]).read_text().strip()
if not sql.startswith('BEGIN;') or not sql.endswith('COMMIT;'):
    sys.exit('Migration must have outer BEGIN/COMMIT')
print(sql[len('BEGIN;'):-len('COMMIT;')])
PY
) || return 1
    printf '\\set module %s\n\\set version %s\n\\set checksum %s\n\\set trimmed_checksum %s\n' "'$module'" "'$version'" "'$checksum'" "'$trimmed_checksum'"
    cat <<'SQL'
SELECT pg_advisory_xact_lock(735192401);
CREATE TABLE IF NOT EXISTS schema_migrations (
    module TEXT NOT NULL,
    version TEXT NOT NULL,
    checksum TEXT NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (module, version)
);
SELECT EXISTS (SELECT 1 FROM schema_migrations WHERE module = :'module' AND version = :'version') AS applied,
       EXISTS (SELECT 1 FROM schema_migrations WHERE module = :'module' AND version = :'version' AND checksum <> :'checksum' AND checksum <> :'trimmed_checksum') AS changed \gset
\if :changed
    DO $$ BEGIN RAISE EXCEPTION 'An applied migration has changed'; END $$;
\endif
\if :applied
    \echo Migration already applied
\else
SQL
    printf '%s\n' "$body"
    cat <<'SQL'
INSERT INTO schema_migrations (module, version, checksum) VALUES (:'module', :'version', :'checksum');
\endif
SQL
}

apply_migration() {
    local target=$1 module=$2 file=$3 script
    script=$(bms_migration_script "$module" "$file") || return 1
    # Keep the history record and schema changes in the same transaction.
    printf 'BEGIN;\n%s\nCOMMIT;\n' "$script" | migration_psql "$target" -v ON_ERROR_STOP=1
}
