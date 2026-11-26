BEGIN;

-- Cache of translations of texts written by users (stage names, notes...), I18N.md §8. The key is the
-- SHA-256 (hex) of the trimmed source text and the target language. manual rows are typed by a user
-- and the translation provider never overwrites them.
CREATE TABLE IF NOT EXISTS text_translations (
    hash CHAR(64) NOT NULL,
    language VARCHAR(8) NOT NULL CHECK (language ~ '^[a-z]{2}$'),
    source_text TEXT NOT NULL,
    translated_text TEXT NOT NULL,
    manual BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (hash, language)
);

COMMIT;
