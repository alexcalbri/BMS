BEGIN;

-- Two-step verification (TOTP, TWO_FACTOR.md) of the server owner and the server administrators. Same tables as
-- tenant V012. The secret is sealed with the data key of this database, so the central database gets the
-- tenant_data_keys table that company databases already have (tenant V006): one data key wrapped with
-- BMS_MASTER_KEY.
CREATE TABLE IF NOT EXISTS tenant_data_keys (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    wrapped_key TEXT NOT NULL CHECK (length(wrapped_key) > 0),
    key_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS application_user_two_factor (
    user_id UUID PRIMARY KEY REFERENCES application_users(id) ON DELETE CASCADE,
    secret_sealed TEXT NOT NULL CHECK (secret_sealed LIKE 'enc:v1:%'),
    enabled_at TIMESTAMPTZ,
    last_used_step BIGINT NOT NULL DEFAULT 0,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    failure_window_start TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS application_user_recovery_codes (
    user_id UUID NOT NULL REFERENCES application_users(id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, code_hash)
);

COMMIT;
