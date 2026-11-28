BEGIN;

-- In-app assistant (GLOBAL_AGENT.md, Core 0.14.1), what needs the person's attention on another device. Idempotent.
-- last_read_at: when the owner last opened the conversation (GET detail). finished_at: when its last turn finished.
-- notification_id: the one live inbox notification of the conversation (pending confirmation, or answer ready), replaced
-- or marked read when it is resolved, so a conversation never has two.
ALTER TABLE public.assistant_conversations ADD COLUMN IF NOT EXISTS last_read_at TIMESTAMPTZ NULL;
ALTER TABLE public.assistant_conversations ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ NULL;
ALTER TABLE public.assistant_conversations ADD COLUMN IF NOT EXISTS notification_id UUID NULL;

-- The badge query: one person's conversations that wait for a decision or finished unseen.
CREATE INDEX IF NOT EXISTS assistant_conversations_attention_idx
    ON public.assistant_conversations (user_id, owner_key)
    WHERE archived_at IS NULL AND (state = 'awaiting_confirmation' OR finished_at IS NOT NULL);

COMMIT;
