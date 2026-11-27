BEGIN;

-- In-app assistant of the Core (GLOBAL_AGENT.md). Everything here belongs to one company; the server-level settings
-- live in the control database's server_settings. Every name is schema-qualified.

-- The company's own switch and its optional API key (one row, id = 1). The key is sealed with the company's data key
-- (SecretBox, "enc:v1:..."); only a hint (prefix and last 4 characters) is ever shown.
CREATE TABLE IF NOT EXISTS public.assistant_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    api_key_enc TEXT NULL,
    api_key_hint TEXT NULL,
    disclosure_accepted_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One conversation of one user. owner_key tells the account's own sessions ("self") from a switcher session opened by
-- someone else ("server:<id>", "company:<code>:<id>"): each sees only its own conversations.
CREATE TABLE IF NOT EXISTS public.assistant_conversations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.application_users (id) ON DELETE CASCADE,
    owner_key TEXT NOT NULL CHECK (pg_catalog.length(owner_key) BETWEEN 1 AND 200),
    title TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL DEFAULT 'idle' CHECK (state IN ('idle', 'running', 'awaiting_confirmation')),
    -- While a turn runs: when its lease ends (a crashed turn frees the conversation then).
    lease_until TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS assistant_conversations_owner_idx
    ON public.assistant_conversations (user_id, owner_key, updated_at DESC);
CREATE INDEX IF NOT EXISTS assistant_conversations_updated_idx ON public.assistant_conversations (updated_at);

-- The messages exactly as they are sent to the API again (content blocks, thinking and tool_use/tool_result
-- included), plus display-only notices (role 'notice', never sent). Append-only for the runtime role (SELECT,
-- INSERT): they go only with their conversation (ON DELETE CASCADE, run as the table owner).
CREATE TABLE IF NOT EXISTS public.assistant_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES public.assistant_conversations (id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'notice')),
    content JSONB NOT NULL CHECK (pg_catalog.jsonb_typeof(content) OPERATOR(pg_catalog.=) 'array'),
    model TEXT NULL,
    stop_reason TEXT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_input_tokens BIGINT NOT NULL DEFAULT 0,
    cache_creation_input_tokens BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS assistant_messages_conversation_idx ON public.assistant_messages (conversation_id, id);

-- The tool calls of a turn paused for the user's confirmation: every call of that model response, in order (position).
-- Calls that ran already (read only) keep their tool_result block in result; the others wait for a decision until
-- expires_at. The rows go when the turn resumes or expires.
CREATE TABLE IF NOT EXISTS public.assistant_pending_calls (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES public.assistant_conversations (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    tool_use_id TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    arguments JSONB NOT NULL,
    label TEXT NOT NULL,
    module_name TEXT NOT NULL,
    destructive BOOLEAN NOT NULL DEFAULT FALSE,
    summary JSONB NOT NULL DEFAULT '[]',
    status TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'done')),
    result JSONB NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ NULL,
    UNIQUE (conversation_id, tool_use_id)
);

CREATE INDEX IF NOT EXISTS assistant_pending_calls_expiry_idx ON public.assistant_pending_calls (status, expires_at);

-- Token usage per day, user and key ("server": the server owner's key, "company": the company's own key), for the
-- monthly limit and the owners' screens. counted_tokens = input + cache creation + output + ceil(cache read / 10).
CREATE TABLE IF NOT EXISTS public.assistant_usage (
    day DATE NOT NULL,
    user_id UUID NOT NULL,
    key_source TEXT NOT NULL CHECK (key_source IN ('server', 'company')),
    requests BIGINT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_input_tokens BIGINT NOT NULL DEFAULT 0,
    cache_creation_input_tokens BIGINT NOT NULL DEFAULT 0,
    counted_tokens BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (day, user_id, key_source)
);

COMMIT;
