BEGIN;

-- Who is really behind a company session opened by the business-owner switcher (an owner of another company of the
-- owner group): that company and that user, kept from the first switch along a chain of switches. Agentes records it
-- as the opener of an agent connected from such a session, so the connection ends when that person's credentials
-- change and when the owner group no longer joins both companies.
ALTER TABLE application_sessions
    ADD COLUMN IF NOT EXISTS switched_from_company TEXT,
    ADD COLUMN IF NOT EXISTS switched_from_user UUID;

COMMIT;
