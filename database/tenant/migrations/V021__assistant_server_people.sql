BEGIN;

-- In-app assistant (GLOBAL_AGENT.md, Core 0.14.1), conversations of server people. Idempotent.
-- The server workspace is a company database whose users are central accounts (the server owner and the administrators):
-- they have no row in this database's application_users, so a conversation cannot reference one. Nothing else changes:
-- every read and write of a conversation is by (id, user_id, owner_key), and the daily job deletes the old ones. A company
-- user is only ever deactivated, never deleted, so no conversation is left without its person.
ALTER TABLE public.assistant_conversations DROP CONSTRAINT IF EXISTS assistant_conversations_user_id_fkey;

COMMIT;
