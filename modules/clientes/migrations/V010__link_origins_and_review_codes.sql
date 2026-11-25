BEGIN;

-- Companies whose copies this company sees now (the ones linked with it). Clientes keeps it in step
-- when a company is linked or unlinked (one row per company, so unlinking hides every copy at once
-- without rewriting customers) and the link job reconciles it with the Core every minute. Every
-- module reads customers through the Core condition CustomerSql.Visible, which checks this table.
CREATE TABLE customer_link_origins (
    company_code VARCHAR(63) PRIMARY KEY,
    added_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Copies visible before this migration keep being visible until the job reconciles the table.
INSERT INTO customer_link_origins (company_code)
SELECT DISTINCT origin_company_code FROM customers WHERE origin_company_code <> '' AND shared
ON CONFLICT DO NOTHING;

-- First full copy of this company's own customers to one linked company, by id; NULL when it has not
-- started (or starts again). New and edited own customers are copied by the pending pass (shared = FALSE).
ALTER TABLE customer_link_jobs ADD COLUMN cursor_id UUID;

-- Why a pair is waiting in "Por revisar", for the screens and agents: name_differs (same email,
-- different name), accounts (both have a linked user account), code, incomplete, copy, edit.
ALTER TABLE customer_merges ADD COLUMN reason_code VARCHAR(32) NOT NULL DEFAULT '';

CREATE INDEX customer_merges_kept_idx ON customer_merges (kept_id);

COMMIT;
