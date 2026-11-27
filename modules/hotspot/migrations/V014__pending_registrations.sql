BEGIN;

-- A guest who asked for an emailed code is a registration without a customer until the code is
-- entered: the customer in Clientes is created only once the address is proven (it used to be
-- created on the first form submission, so anyone could fill Clientes with addresses they typed).
-- Only form registrations can be pending; every other registration keeps its owner.
ALTER TABLE hotspot_registrations
    DROP CONSTRAINT hotspot_registrations_owner,
    ADD CONSTRAINT hotspot_registrations_owner CHECK (
        (customer_id IS NULL) <> (user_id IS NULL)
        OR (roamed_from IS NOT NULL AND customer_id IS NULL AND user_id IS NULL)
        OR (access_mode = 'formulario' AND customer_id IS NULL AND user_id IS NULL AND roamed_from IS NULL)
    );

-- Codes sent in the last hour by device and by destination address (limits of the form).
CREATE INDEX IF NOT EXISTS hotspot_registrations_mac_created_idx ON hotspot_registrations (mac, created_at);
CREATE INDEX IF NOT EXISTS hotspot_codes_created_idx ON hotspot_codes (created_at);

COMMIT;
