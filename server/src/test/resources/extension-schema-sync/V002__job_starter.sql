BEGIN;
-- Without IF NOT EXISTS on purpose: a company where the column already exists makes this migration fail.
ALTER TABLE ext_secasync__jobs ADD COLUMN created_by_user UUID;
COMMIT;
