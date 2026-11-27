BEGIN;

-- The code emailed by the form is one session: it starts at the first activation (first device) and a
-- second device that enters the same code ends at the same moment. At most 2 devices per code.
ALTER TABLE hotspot_codes
    ADD COLUMN activated_at TIMESTAMPTZ,
    ADD COLUMN ends_at TIMESTAMPTZ,
    ADD CONSTRAINT hotspot_codes_activation CHECK ((activated_at IS NULL) = (ends_at IS NULL));

-- One row per device slot of a code. The primary key allows slots 1 and 2 only, so a third device cannot be
-- stored whatever the requests do at the same time.
CREATE TABLE hotspot_code_devices (
    code_id UUID NOT NULL REFERENCES hotspot_codes(id) ON DELETE CASCADE,
    slot SMALLINT NOT NULL CHECK (slot BETWEEN 1 AND 2),
    mac VARCHAR(17) NOT NULL CHECK (mac ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (code_id, slot),
    UNIQUE (code_id, mac)
);

-- New customers the form creates per origin address, in one-hour windows.
ALTER TABLE hotspot_ip_throttle DROP CONSTRAINT IF EXISTS hotspot_ip_throttle_action_check;
ALTER TABLE hotspot_ip_throttle ADD CONSTRAINT hotspot_ip_throttle_action_check CHECK (action IN ('register', 'code', 'customer'));

-- Codes sent in the last hour by device and by destination address (limits of the form).
CREATE INDEX IF NOT EXISTS hotspot_registrations_mac_created_idx ON hotspot_registrations (mac, created_at);
CREATE INDEX IF NOT EXISTS hotspot_codes_created_idx ON hotspot_codes (created_at);

COMMIT;
