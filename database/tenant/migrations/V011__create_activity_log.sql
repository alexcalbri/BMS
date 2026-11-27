BEGIN;

-- Company activity log (ACTIVITY_LOG.md §2): who did what, when and where, in the same transaction
-- as the change. Append-only: the runtime role gets only SELECT and INSERT (granted by the server),
-- and the triggers below refuse UPDATE, DELETE and TRUNCATE for everyone, the table owner included.
-- Tamper evidence (ACTIVITY_LOG.md §9): every committed row is sealed into a hash chain
-- (chain_seq, prev_hash, row_hash) at commit time; scripts/ubuntu/activity-harden.sh later gives the
-- table and its functions to the role bms_audit, outside the runtime role's membership.
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
    origin TEXT NULL,
    -- Hash chain, filled only by activity_log_seal() when the inserting transaction commits.
    chain_seq BIGINT NULL,
    prev_hash TEXT NULL,
    row_hash TEXT NULL
) WITH (fillfactor = 90);

CREATE INDEX IF NOT EXISTS activity_log_occurred_idx ON activity_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS activity_log_actor_idx ON activity_log (actor_user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS activity_log_module_idx ON activity_log (module_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS activity_log_entity_idx ON activity_log (entity_type, entity_id);

-- Head of the chain (one row). Only the sealing function writes it; the runtime role has no grant.
CREATE TABLE IF NOT EXISTS activity_log_chain_head (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    last_seq BIGINT NOT NULL,
    last_hash TEXT NOT NULL
);
INSERT INTO activity_log_chain_head (id, last_seq, last_hash)
VALUES (1, 0, repeat('0', 64)) ON CONFLICT (id) DO NOTHING;

-- UPDATE, DELETE and TRUNCATE are refused, except the one update the sealing trigger makes: filling
-- the empty chain columns of a row, every other column unchanged.
CREATE OR REPLACE FUNCTION activity_log_append_only() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND pg_trigger_depth() > 1
        AND OLD.chain_seq IS NULL AND OLD.row_hash IS NULL AND OLD.prev_hash IS NULL
        AND NEW.chain_seq IS NOT NULL AND NEW.row_hash IS NOT NULL AND NEW.prev_hash IS NOT NULL
        AND (NEW.id, NEW.occurred_at, NEW.actor_kind, NEW.actor_user_id, NEW.actor_name, NEW.actor_display, NEW.agent_name,
             NEW.real_actor_kind, NEW.real_actor_user_id, NEW.real_actor_name, NEW.module_id, NEW.entity_type, NEW.entity_id,
             NEW.entity_label, NEW.action, NEW.summary, NEW.changes, NEW.origin)
            IS NOT DISTINCT FROM
            (OLD.id, OLD.occurred_at, OLD.actor_kind, OLD.actor_user_id, OLD.actor_name, OLD.actor_display, OLD.agent_name,
             OLD.real_actor_kind, OLD.real_actor_user_id, OLD.real_actor_name, OLD.module_id, OLD.entity_type, OLD.entity_id,
             OLD.entity_label, OLD.action, OLD.summary, OLD.changes, OLD.origin)
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'activity_log is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- A new row gets its id from the sequence, the time of its transaction and an empty chain: nothing
-- an insert sends can backdate a row, reuse an id or pre-fill its hashes.
CREATE OR REPLACE FUNCTION activity_log_before_insert() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM currval('public.activity_log_id_seq'::regclass) THEN
        RAISE EXCEPTION 'activity_log ids come from its sequence' USING ERRCODE = 'insufficient_privilege';
    END IF;
    NEW.occurred_at := CURRENT_TIMESTAMP;
    NEW.chain_seq := NULL;
    NEW.prev_hash := NULL;
    NEW.row_hash := NULL;
    RETURN NEW;
END;
$$;

-- Seals one committed row into the chain: runs when the inserting transaction commits (deferred), under
-- one transaction advisory lock held only until that commit ends, so concurrent writers chain in
-- commit order without holding a lock for the rest of their work. row_hash = sha256(prev_hash || '|' ||
-- canonical JSON of the stored fields); ActivityIntegrity recomputes the same expression.
CREATE OR REPLACE FUNCTION activity_log_seal() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    head_seq BIGINT;
    head_hash TEXT;
    sealed BIGINT;
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(735192402);
    SELECT last_seq, last_hash INTO head_seq, head_hash FROM public.activity_log_chain_head WHERE id = 1 FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'activity_log_chain_head is missing';
    END IF;
    UPDATE public.activity_log AS row_
    SET chain_seq = head_seq + 1,
        prev_hash = head_hash,
        row_hash = pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(head_hash || '|' || pg_catalog.jsonb_build_array(
            head_seq + 1, row_.id, pg_catalog.to_char(row_.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            row_.actor_kind, row_.actor_user_id, row_.actor_name, row_.actor_display, row_.agent_name, row_.real_actor_kind,
            row_.real_actor_user_id, row_.real_actor_name, row_.module_id, row_.entity_type, row_.entity_id, row_.entity_label,
            row_.action, row_.summary, row_.changes, row_.origin)::text, 'UTF8')), 'hex')
    WHERE row_.id = NEW.id AND row_.chain_seq IS NULL
    RETURNING row_.row_hash INTO head_hash;
    GET DIAGNOSTICS sealed = ROW_COUNT;
    IF sealed = 1 THEN
        UPDATE public.activity_log_chain_head SET last_seq = head_seq + 1, last_hash = head_hash WHERE id = 1;
    END IF;
    RETURN NULL;
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

DROP TRIGGER IF EXISTS activity_log_before_insert ON activity_log;
CREATE TRIGGER activity_log_before_insert
    BEFORE INSERT ON activity_log
    FOR EACH ROW EXECUTE FUNCTION activity_log_before_insert();

DROP TRIGGER IF EXISTS activity_log_seal ON activity_log;
CREATE CONSTRAINT TRIGGER activity_log_seal
    AFTER INSERT ON activity_log
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION activity_log_seal();

-- Nobody but the owner (through the sealing function) touches the head.
REVOKE ALL ON activity_log_chain_head FROM PUBLIC;
REVOKE ALL ON FUNCTION activity_log_seal() FROM PUBLIC;

COMMIT;
