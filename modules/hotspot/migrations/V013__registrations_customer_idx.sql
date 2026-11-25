BEGIN;

-- Indice por cliente: Clientes cuenta y reapunta registros al vincular o separar empresas.
CREATE INDEX IF NOT EXISTS hotspot_registrations_customer_idx ON hotspot_registrations (customer_id);

COMMIT;
