BEGIN;

-- How a session passed its second step (TWO_FACTOR.md §9): 'totp' or 'recovery_code' at sign-in, 'inherited' when
-- the mark came from the session it was switched from. A session opened with a recovery code may replace a lost
-- authenticator with the password alone for a short time.
ALTER TABLE application_sessions ADD COLUMN IF NOT EXISTS second_factor_method TEXT
    CHECK (second_factor_method IN ('totp', 'recovery_code', 'inherited'));

-- A new authenticator being set up to replace the current one: sealed like the secret, active only once confirmed
-- with a code of the new authenticator.
ALTER TABLE application_user_two_factor ADD COLUMN IF NOT EXISTS pending_secret_sealed TEXT
    CHECK (pending_secret_sealed IS NULL OR pending_secret_sealed LIKE 'enc:v1:%');

COMMIT;
