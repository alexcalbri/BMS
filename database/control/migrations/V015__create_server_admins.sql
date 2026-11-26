BEGIN;

-- Server administrators (SERVER_ADMINS.md): server-level users the server owner creates, each with
-- an explicit set of server permissions and an optional list of companies. They have NO PostgreSQL
-- role: application_users.postgres_role only holds their unique login name, and privileged work
-- keeps running as the server owner's role. Passwords are PBKDF2 hashes
-- ("pbkdf2_sha256$<iterations>$<salt>$<hash>"), like the application credentials of companies.
CREATE TABLE IF NOT EXISTS server_admins (
    user_id UUID PRIMARY KEY REFERENCES application_users (id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    all_companies BOOLEAN NOT NULL DEFAULT FALSE,
    password_hash TEXT NOT NULL CHECK (password_hash LIKE 'pbkdf2_sha256$%'),
    password_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Permission ids are validated by the server; unknown stored values are ignored when read.
CREATE TABLE IF NOT EXISTS server_admin_permissions (
    user_id UUID NOT NULL REFERENCES server_admins (user_id) ON DELETE CASCADE,
    permission TEXT NOT NULL CHECK (permission ~ '^server\.[a-z_.]{1,80}$'),
    PRIMARY KEY (user_id, permission)
);

-- Companies an administrator may act on when all_companies is false (empty = none).
CREATE TABLE IF NOT EXISTS server_admin_companies (
    user_id UUID NOT NULL REFERENCES server_admins (user_id) ON DELETE CASCADE,
    company_code VARCHAR(63) NOT NULL REFERENCES companies (code) ON DELETE CASCADE ON UPDATE CASCADE,
    PRIMARY KEY (user_id, company_code)
);

-- A user is never both server owner and server administrator, and an administrator's login is
-- never the name of a PostgreSQL role (it could otherwise be mistaken for a database login).
-- Both triggers take the same transaction-scoped advisory lock on the user id first, so two
-- concurrent transactions cannot insert the same user as owner and as administrator (each would
-- otherwise not see the other's uncommitted row).
CREATE OR REPLACE FUNCTION server_admins_check_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('bms.server_identity:' || NEW.user_id::text, 0));
    IF EXISTS (SELECT 1 FROM server_owners WHERE user_id = NEW.user_id) THEN
        RAISE EXCEPTION 'A server owner cannot be a server administrator' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM application_users users JOIN pg_roles roles ON roles.rolname = users.postgres_role
        WHERE users.id = NEW.user_id
    ) THEN
        RAISE EXCEPTION 'The login of a server administrator cannot be a PostgreSQL role' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION server_owners_check_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('bms.server_identity:' || NEW.user_id::text, 0));
    IF EXISTS (SELECT 1 FROM server_admins WHERE user_id = NEW.user_id) THEN
        RAISE EXCEPTION 'A server administrator cannot be a server owner' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS server_admins_identity ON server_admins;
CREATE TRIGGER server_admins_identity BEFORE INSERT OR UPDATE OF user_id ON server_admins
    FOR EACH ROW EXECUTE FUNCTION server_admins_check_identity();

DROP TRIGGER IF EXISTS server_owners_identity ON server_owners;
CREATE TRIGGER server_owners_identity BEFORE INSERT OR UPDATE OF user_id ON server_owners
    FOR EACH ROW EXECUTE FUNCTION server_owners_check_identity();

-- Workspace staff accounts whose credentials a server administrator controls: created by them, or
-- given a password or a new email (where password recovery goes) by them. user_id is
-- application_users.id in the server workspace database, so there is no foreign key to it. When that
-- administrator is deactivated, deleted or loses server.workspace, these accounts are deactivated and
-- their sessions revoked. A password or email set later by the owner moves the row to the owner.
CREATE TABLE IF NOT EXISTS server_workspace_staff (
    user_id UUID PRIMARY KEY,
    created_by UUID NOT NULL REFERENCES application_users (id),
    created_by_kind TEXT NOT NULL CHECK (created_by_kind IN ('server_owner', 'server_admin')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS server_workspace_staff_created_by_idx ON server_workspace_staff (created_by);

-- Who made each audited change: the administrator (or owner, business owner, system), never the
-- role that executed it.
ALTER TABLE provisioning_audit_log
    ADD COLUMN IF NOT EXISTS actor_user_id UUID,
    ADD COLUMN IF NOT EXISTS actor_kind TEXT
        CHECK (actor_kind IS NULL OR actor_kind IN ('server_owner', 'server_admin', 'business_owner', 'system'));

CREATE INDEX IF NOT EXISTS provisioning_audit_log_actor_user_idx
    ON provisioning_audit_log (actor_user_id)
    WHERE actor_user_id IS NOT NULL;

COMMIT;
