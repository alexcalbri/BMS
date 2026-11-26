BEGIN;

-- Language of each user (I18N.md §7): 'es', 'en' or NULL to follow the company default language.
-- Applies to the central database (server owners) and to every company database.
ALTER TABLE application_users
    ADD COLUMN IF NOT EXISTS language VARCHAR(8)
        CHECK (language IS NULL OR language ~ '^[a-z]{2}$');

COMMIT;
