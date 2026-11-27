BEGIN;

-- Who really connected an agent: 'self' (the account itself), 'server' (a server owner or administrator through
-- the company switcher: opener_user is the central user) or 'company' (a business owner of another company of the
-- owner group through the business-owner switcher: opener_company + opener_user). NULL = connections made before
-- this release ("origen desconocido"). Used to end the connection when that person's credentials change and,
-- on every use, when that person may no longer enter the company.
ALTER TABLE agent_tokens
    ADD COLUMN IF NOT EXISTS opener_kind TEXT CHECK (opener_kind IN ('self', 'server', 'company')),
    ADD COLUMN IF NOT EXISTS opener_user UUID,
    ADD COLUMN IF NOT EXISTS opener_company TEXT;

CREATE INDEX IF NOT EXISTS agent_tokens_opener_idx ON agent_tokens (opener_kind, opener_user) WHERE revoked_at IS NULL;

COMMIT;
