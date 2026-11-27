BEGIN;

-- State of the customer's email, for customers whose email a module asks the person to confirm (the WiFi form).
--   NULL         unknown / not applicable (every customer that already exists, imports, staff-created ones): shown without a badge.
--   'unverified' created by a form that emails a code, and the code was not entered yet.
--   'verified'   the person entered the code that was emailed to that address (email_verified_at says when).
ALTER TABLE customers
    ADD COLUMN email_status VARCHAR(10),
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    ADD CONSTRAINT customers_email_status_check CHECK (
        (email_status IS NULL AND email_verified_at IS NULL)
        OR (email_status = 'unverified' AND email_verified_at IS NULL)
        OR (email_status = 'verified' AND email_verified_at IS NOT NULL)
    );

-- Another email is another address: whoever changes it (staff, an import, an agent) goes back to "unknown".
CREATE FUNCTION customers_reset_email_status() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF lower(btrim(NEW.primary_email)) IS DISTINCT FROM lower(btrim(OLD.primary_email))
       AND NEW.email_status IS NOT DISTINCT FROM OLD.email_status THEN
        NEW.email_status := NULL;
        NEW.email_verified_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER customers_reset_email_status
    BEFORE UPDATE OF primary_email ON customers
    FOR EACH ROW EXECUTE FUNCTION customers_reset_email_status();

-- Filter of the customer list by state.
CREATE INDEX customers_email_status_idx ON customers (email_status) WHERE email_status IS NOT NULL;

COMMIT;
