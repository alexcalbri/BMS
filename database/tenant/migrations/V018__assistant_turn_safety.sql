BEGIN;

-- In-app assistant (GLOBAL_AGENT.md), safety of a turn. Idempotent.
-- lease_token: the turn that holds the conversation; every write of a turn checks it, so a turn whose lease ran out
-- can never write over the one that took the conversation after it.
ALTER TABLE public.assistant_conversations ADD COLUMN IF NOT EXISTS lease_token UUID NULL;
-- closed: a conversation that cannot go on (the model declined after tool results); the user starts a new one.
ALTER TABLE public.assistant_conversations DROP CONSTRAINT IF EXISTS assistant_conversations_state_check;
ALTER TABLE public.assistant_conversations ADD CONSTRAINT assistant_conversations_state_check
    CHECK (state IN ('idle', 'running', 'awaiting_confirmation', 'closed'));

-- executing: an approved call claimed by the turn that runs it (once); it is done with its result in the same row, so
-- a call that ran is never run again and is always reported as it really ended.
ALTER TABLE public.assistant_pending_calls DROP CONSTRAINT IF EXISTS assistant_pending_calls_status_check;
ALTER TABLE public.assistant_pending_calls ADD CONSTRAINT assistant_pending_calls_status_check
    CHECK (status IN ('pending', 'approved', 'rejected', 'executing', 'done'));
-- What the module resolved for the confirmation card (McpTool.preview) and whether the call sends something out.
ALTER TABLE public.assistant_pending_calls ADD COLUMN IF NOT EXISTS preview JSONB NOT NULL DEFAULT '[]';
ALTER TABLE public.assistant_pending_calls ADD COLUMN IF NOT EXISTS preview_error TEXT NULL;
ALTER TABLE public.assistant_pending_calls ADD COLUMN IF NOT EXISTS outbound BOOLEAN NOT NULL DEFAULT FALSE;

COMMIT;
