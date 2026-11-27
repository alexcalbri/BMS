BEGIN;

-- A session that really passed a second step (authenticator or recovery code) at sign-in, or inherited it from
-- the session it was switched from (TWO_FACTOR.md §2): only such a session may switch into an account that uses
-- two-step verification. Enabling two-step verification in a session does not set it.
ALTER TABLE application_sessions ADD COLUMN IF NOT EXISTS second_factor_at TIMESTAMPTZ;

-- Delayed recovery by email (TWO_FACTOR.md §7): when the first warning was mailed, and the public base its cancel
-- link used, so the job can send it again (with a new cancel token) when the first send failed.
ALTER TABLE application_user_2fa_email_recovery
    ADD COLUMN IF NOT EXISTS warning_mailed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS notice_base TEXT;
-- Processes started before this migration: their warning was sent at the start (or its failure was logged then).
UPDATE application_user_2fa_email_recovery SET warning_mailed_at = started_at WHERE warning_mailed_at IS NULL;

COMMIT;
