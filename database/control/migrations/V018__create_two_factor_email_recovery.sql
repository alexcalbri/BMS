BEGIN;

-- Delayed recovery of two-step verification by email (TWO_FACTOR.md §7). One row per process; at most one open
-- (pending or code_sent) per account. Only hashes of the cancel token and of the code are stored. Times come from
-- the server's clock (code_due_at = started_at + the wait); the job claims a row with one UPDATE, so two runs never
-- leave two valid codes.
CREATE TABLE IF NOT EXISTS application_user_2fa_email_recovery (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES application_users(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('pending', 'code_sent', 'completed', 'cancelled', 'expired')),
    email TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    started_from TEXT NOT NULL,
    code_due_at TIMESTAMPTZ NOT NULL,
    cancel_token_hash CHAR(64) NOT NULL UNIQUE,
    code_hash CHAR(64),
    code_issued_at TIMESTAMPTZ,
    code_expires_at TIMESTAMPTZ,
    code_mailed_at TIMESTAMPTZ,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    failure_window_start TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    end_reason TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS application_user_2fa_email_recovery_open
    ON application_user_2fa_email_recovery (user_id) WHERE status IN ('pending', 'code_sent');
CREATE INDEX IF NOT EXISTS application_user_2fa_email_recovery_due
    ON application_user_2fa_email_recovery (code_due_at) WHERE status IN ('pending', 'code_sent');

COMMIT;
