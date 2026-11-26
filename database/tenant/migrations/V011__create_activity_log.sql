BEGIN;

-- Company activity log (ACTIVITY_LOG.md §2): who did what, when and where, in the same transaction
-- as the change. Append-only: the runtime role gets only SELECT and INSERT (granted by the server),
-- and the trigger below refuses UPDATE, DELETE and TRUNCATE for everyone, the table owner included.
CREATE TABLE IF NOT EXISTS activity_log (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_kind TEXT NOT NULL CHECK (actor_kind ~ '^[a-z][a-z_]{0,31}$'),
    actor_user_id UUID NULL,
    actor_name TEXT NOT NULL,
    actor_display TEXT NOT NULL,
    agent_name TEXT NULL,
    real_actor_kind TEXT NULL,
    real_actor_user_id UUID NULL,
    real_actor_name TEXT NULL,
    module_id TEXT NOT NULL CHECK (module_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    entity_type TEXT NOT NULL,
    entity_id TEXT NULL,
    entity_label TEXT NOT NULL DEFAULT '',
    action TEXT NOT NULL CHECK (action ~ '^[a-z][a-z0-9_]{0,63}$'),
    summary TEXT NOT NULL,
    changes JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(changes) = 'array'),
    origin TEXT NULL
);

CREATE INDEX IF NOT EXISTS activity_log_occurred_idx ON activity_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS activity_log_actor_idx ON activity_log (actor_user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS activity_log_module_idx ON activity_log (module_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS activity_log_entity_idx ON activity_log (entity_type, entity_id);

CREATE OR REPLACE FUNCTION activity_log_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'activity_log is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

DROP TRIGGER IF EXISTS activity_log_no_change ON activity_log;
CREATE TRIGGER activity_log_no_change
    BEFORE UPDATE OR DELETE ON activity_log
    FOR EACH ROW EXECUTE FUNCTION activity_log_append_only();

DROP TRIGGER IF EXISTS activity_log_no_truncate ON activity_log;
CREATE TRIGGER activity_log_no_truncate
    BEFORE TRUNCATE ON activity_log
    FOR EACH STATEMENT EXECUTE FUNCTION activity_log_append_only();

COMMIT;
