BEGIN;

-- A business owner's token that may also act in the other companies of its owner group. Off for
-- every existing token: the owner turns it on when creating a connection.
ALTER TABLE agent_tokens ADD COLUMN group_scope BOOLEAN NOT NULL DEFAULT FALSE;

-- What agents of other companies of the owner group did in this company. agent_activity cannot hold
-- it: its rows belong to tokens of this company.
CREATE TABLE agent_group_activity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    origin_company TEXT NOT NULL,
    agent_name TEXT NOT NULL,
    user_name TEXT NOT NULL,
    tool TEXT NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('ok', 'error', 'denied')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX agent_group_activity_created_idx ON agent_group_activity (created_at DESC);

COMMIT;
