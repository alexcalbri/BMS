BEGIN;

-- Default language of each company (I18N.md §7): users without a language of their own see the
-- server in it, and texts written in the company are assumed to be in it.
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS default_language VARCHAR(8) NOT NULL DEFAULT 'es'
        CHECK (default_language ~ '^[a-z]{2}$');

COMMIT;
