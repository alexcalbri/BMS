BEGIN;

-- Two-step verification (TOTP, TWO_FACTOR.md) of the users of this company. The secret is sealed with the
-- data key of this database (tenant_data_keys, "enc:v1:"); enabled_at NULL means an enrolment not yet confirmed.
-- last_used_step stops a code from being used twice; failed_attempts/failure_window_start throttle wrong codes
-- per account across restarts.
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

-- One-time recovery codes, only as SHA-256 hashes (salted with the user id); used_at set when spent.
CREATE TABLE IF NOT EXISTS application_user_recovery_codes (
    user_id UUID NOT NULL REFERENCES application_users(id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, code_hash)
);

COMMIT;
