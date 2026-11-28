BEGIN;

-- In-app assistant (GLOBAL_AGENT.md), approval bound to what runs. Idempotent.
-- preview_hash: fingerprint of the confirmation card the user saw (the tool's preview rows, in order, or its error),
-- taken when the turn paused. Before an approved call runs, the preview is resolved again and compared: when it
-- differs, the call is NOT run and the model is told to ask again. NULL: the tool has no preview.
ALTER TABLE public.assistant_pending_calls ADD COLUMN IF NOT EXISTS preview_hash TEXT NULL;

COMMIT;
