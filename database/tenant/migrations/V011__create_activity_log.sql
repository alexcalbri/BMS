BEGIN;

-- Company activity log (ACTIVITY_LOG.md §2): who did what, when and where, in the same transaction
-- as the change. Append-only: the runtime role gets only SELECT and INSERT (granted by the server),
-- and the triggers below refuse UPDATE, DELETE and TRUNCATE for everyone, the table owner included.
-- Tamper evidence (ACTIVITY_LOG.md §9): every committed row is sealed into a hash chain
-- (activity_log_chain: chain_seq, id, prev_hash, row_hash) at commit time; scripts/ubuntu/activity-harden.sh
-- later gives these tables and their functions to the role bms_audit, outside the runtime role's membership,
-- and normalizes them to exactly what this file creates (activity-harden.sql carries the same definitions).
-- Every name is schema-qualified so the file means the same whatever search_path it runs with.
CREATE TABLE IF NOT EXISTS public.activity_log (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_kind TEXT NOT NULL CHECK (actor_kind OPERATOR(pg_catalog.~) '^[a-z][a-z_]{0,31}$'),
    actor_user_id UUID NULL,
    actor_name TEXT NOT NULL,
    actor_display TEXT NOT NULL,
    agent_name TEXT NULL,
    real_actor_kind TEXT NULL,
    real_actor_user_id UUID NULL,
    real_actor_name TEXT NULL,
    module_id TEXT NOT NULL CHECK (module_id OPERATOR(pg_catalog.~) '^[a-z][a-z0-9_]{0,62}$'),
    entity_type TEXT NOT NULL,
    entity_id TEXT NULL,
    entity_label TEXT NOT NULL DEFAULT '',
    action TEXT NOT NULL CHECK (action OPERATOR(pg_catalog.~) '^[a-z][a-z0-9_]{0,63}$'),
    summary TEXT NOT NULL,
    changes JSONB NOT NULL DEFAULT '[]' CHECK (pg_catalog.jsonb_typeof(changes) OPERATOR(pg_catalog.=) 'array'),
    origin TEXT NULL
);

CREATE INDEX IF NOT EXISTS activity_log_occurred_idx ON public.activity_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS activity_log_actor_idx ON public.activity_log (actor_user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS activity_log_module_idx ON public.activity_log (module_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS activity_log_entity_idx ON public.activity_log (entity_type, entity_id);

-- The chain: one row per sealed activity_log row, in commit order. Insert-only, written only by the sealing
-- function; the runtime role may read it. A separate narrow table (rather than columns updated at commit)
-- keeps sealing a large transaction cheap and lets activity_log refuse every UPDATE.
CREATE TABLE IF NOT EXISTS public.activity_log_chain (
    chain_seq BIGINT PRIMARY KEY,
    id BIGINT NOT NULL UNIQUE,
    prev_hash TEXT NOT NULL,
    row_hash TEXT NOT NULL
);

-- Head of the chain (one row). Only the sealing function writes last_seq/last_hash; the runtime role may read it.
-- hardened_at / hardened_seq: when activity-harden.sh first gave the log to bms_audit and how far the chain
-- reached then (rows up to hardened_seq were written while the server owner's role owned the log: not covered).
CREATE TABLE IF NOT EXISTS public.activity_log_chain_head (
    id SMALLINT PRIMARY KEY CHECK (id OPERATOR(pg_catalog.=) 1),
    last_seq BIGINT NOT NULL,
    last_hash TEXT NOT NULL,
    hardened_at TIMESTAMPTZ NULL,
    hardened_seq BIGINT NULL
);
INSERT INTO public.activity_log_chain_head (id, last_seq, last_hash)
VALUES (1, 0, pg_catalog.repeat('0', 64)) ON CONFLICT (id) DO NOTHING;

-- UPDATE, DELETE and TRUNCATE of the log and of its chain are refused, for everyone.
CREATE OR REPLACE FUNCTION public.activity_log_append_only() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- A new row gets its id from the sequence, the time of its transaction and nothing else: nothing an insert
-- sends can backdate a row or reuse an id. The first row of a transaction (or after its rows were sealed by
-- SET CONSTRAINTS ... IMMEDIATE) is marked in transaction-local settings: only that row queues the sealing
-- trigger (its WHEN), and the seal starts reading at its id. A value set by hand can only make the seal
-- read more, or leave the transaction's rows unsealed, which verification reports.
-- Writers chain at commit under one lock per company: a transaction in REPEATABLE READ or SERIALIZABLE would
-- fail there when another one committed first, so it is refused here, clearly; and a writer without a
-- lock_timeout gets 10 s for the rest of its transaction, so a commit waiting for that lock fails instead of
-- hanging (statement_timeout does not apply to COMMIT).
CREATE OR REPLACE FUNCTION public.activity_log_before_insert() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM pg_catalog.currval('public.activity_log_id_seq'::pg_catalog.regclass) THEN
        RAISE EXCEPTION 'activity_log ids come from its sequence' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'activity_log rows must be written in a READ COMMITTED transaction (this one is %)',
            pg_catalog.current_setting('transaction_isolation') USING ERRCODE = 'feature_not_supported';
    END IF;
    IF pg_catalog.current_setting('lock_timeout') = '0' THEN
        PERFORM pg_catalog.set_config('lock_timeout', '10s', true);
    END IF;
    IF pg_catalog.current_setting('bms.activity_seal_pending', true) IS DISTINCT FROM 'on' THEN
        PERFORM pg_catalog.set_config('bms.activity_seal_pending', 'on', true);
        PERFORM pg_catalog.set_config('bms.activity_seal_first', NEW.id::TEXT, true);
    END IF;
    NEW.occurred_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

-- Seals the transaction's rows into the chain when it commits (deferred), under one transaction advisory
-- lock held only until that commit ends, so concurrent writers chain in commit order without holding a lock
-- for the rest of their work. One firing per transaction: it seals every unsealed row from the first id of
-- the transaction on, in id order, in one INSERT, and writes the head once: O(n) whatever the size.
-- row_hash = sha256(prev_hash || '|' || canonical JSON of the stored fields); ActivityIntegrity recomputes it.
CREATE OR REPLACE FUNCTION public.activity_log_seal() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    first_text TEXT := pg_catalog.current_setting('bms.activity_seal_first', true);
    first_id BIGINT := NEW.id;
    head_seq BIGINT;
    head_hash TEXT;
    ids BIGINT[] := '{}';
    seqs BIGINT[] := '{}';
    prevs TEXT[] := '{}';
    hashes TEXT[] := '{}';
    pending RECORD;
BEGIN
    IF first_text ~ '^[0-9]{1,18}$' AND first_text::BIGINT < first_id THEN
        first_id := first_text::BIGINT;
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(735192402);
    SELECT last_seq, last_hash INTO head_seq, head_hash FROM public.activity_log_chain_head WHERE id = 1 FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'activity_log_chain_head is missing';
    END IF;
    -- Rows of other transactions are either sealed already (committed) or invisible (in progress).
    FOR pending IN
        SELECT row_.id, pg_catalog.jsonb_build_array(
            row_.id, pg_catalog.to_char(row_.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            row_.actor_kind, row_.actor_user_id, row_.actor_name, row_.actor_display, row_.agent_name, row_.real_actor_kind,
            row_.real_actor_user_id, row_.real_actor_name, row_.module_id, row_.entity_type, row_.entity_id, row_.entity_label,
            row_.action, row_.summary, row_.changes, row_.origin) AS fields
        FROM public.activity_log AS row_
        WHERE row_.id >= first_id
          AND NOT EXISTS (SELECT 1 FROM public.activity_log_chain AS link WHERE link.id = row_.id)
        ORDER BY row_.id
    LOOP
        ids := ids || pending.id;
        prevs := prevs || head_hash;
        head_seq := head_seq + 1;
        seqs := seqs || head_seq;
        head_hash := pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
            head_hash || '|' || (pg_catalog.jsonb_build_array(head_seq) || pending.fields)::TEXT, 'UTF8')), 'hex');
        hashes := hashes || head_hash;
    END LOOP;
    IF pg_catalog.cardinality(ids) > 0 THEN
        INSERT INTO public.activity_log_chain (chain_seq, id, prev_hash, row_hash)
        SELECT sealing.seq, sealing.id, sealing.prev, sealing.hash
        FROM ROWS FROM (pg_catalog.unnest(seqs), pg_catalog.unnest(ids), pg_catalog.unnest(prevs), pg_catalog.unnest(hashes))
            AS sealing (seq, id, prev, hash);
        UPDATE public.activity_log_chain_head SET last_seq = head_seq, last_hash = head_hash WHERE id = 1;
    END IF;
    -- Rows inserted after this (after SET CONSTRAINTS ... IMMEDIATE) queue a new firing.
    PERFORM pg_catalog.set_config('bms.activity_seal_pending', '', true);
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS activity_log_no_change ON public.activity_log;
CREATE TRIGGER activity_log_no_change
    BEFORE UPDATE OR DELETE ON public.activity_log
    FOR EACH ROW EXECUTE FUNCTION public.activity_log_append_only();

DROP TRIGGER IF EXISTS activity_log_no_truncate ON public.activity_log;
CREATE TRIGGER activity_log_no_truncate
    BEFORE TRUNCATE ON public.activity_log
    FOR EACH STATEMENT EXECUTE FUNCTION public.activity_log_append_only();

DROP TRIGGER IF EXISTS activity_log_before_insert ON public.activity_log;
CREATE TRIGGER activity_log_before_insert
    BEFORE INSERT ON public.activity_log
    FOR EACH ROW EXECUTE FUNCTION public.activity_log_before_insert();

DROP TRIGGER IF EXISTS activity_log_seal ON public.activity_log;
CREATE CONSTRAINT TRIGGER activity_log_seal
    AFTER INSERT ON public.activity_log
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.id::TEXT OPERATOR(pg_catalog.=) pg_catalog.current_setting('bms.activity_seal_first', true))
    EXECUTE FUNCTION public.activity_log_seal();

DROP TRIGGER IF EXISTS activity_log_chain_no_change ON public.activity_log_chain;
CREATE TRIGGER activity_log_chain_no_change
    BEFORE UPDATE OR DELETE ON public.activity_log_chain
    FOR EACH ROW EXECUTE FUNCTION public.activity_log_append_only();

DROP TRIGGER IF EXISTS activity_log_chain_no_truncate ON public.activity_log_chain;
CREATE TRIGGER activity_log_chain_no_truncate
    BEFORE TRUNCATE ON public.activity_log_chain
    FOR EACH STATEMENT EXECUTE FUNCTION public.activity_log_append_only();

-- Nobody but the owner (through the sealing function) writes the chain and its head.
REVOKE ALL ON public.activity_log_chain FROM PUBLIC;
REVOKE ALL ON public.activity_log_chain_head FROM PUBLIC;
REVOKE ALL ON FUNCTION public.activity_log_seal() FROM PUBLIC;

COMMIT;
