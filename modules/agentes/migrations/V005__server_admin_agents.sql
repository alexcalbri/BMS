BEGIN;

-- A server administrator's agent at server level (ACTIVITY_LOG.md §7): stored in the server
-- workspace, its user_id is the administrator's central user and it only ever gets the two audit
-- read tools. It is never a server owner's token.
ALTER TABLE agent_tokens ADD COLUMN IF NOT EXISTS server_admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_tokens DROP CONSTRAINT IF EXISTS agent_tokens_server_admin_not_owner;
ALTER TABLE agent_tokens ADD CONSTRAINT agent_tokens_server_admin_not_owner CHECK (NOT (server_admin AND server_owner));

COMMIT;
