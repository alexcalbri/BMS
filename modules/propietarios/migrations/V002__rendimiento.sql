BEGIN;

-- Busqueda de texto en lotes (listLots): lower(col) LIKE '%texto%' no puede usar un indice normal; con trigramas si.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS propietarios_lots_lot_number_trgm_idx ON propietarios_lots USING gin (lower(lot_number) gin_trgm_ops) WHERE archived_at IS NULL;
CREATE INDEX IF NOT EXISTS propietarios_lots_register_trgm_idx ON propietarios_lots USING gin (lower(register_number) gin_trgm_ops) WHERE archived_at IS NULL;
CREATE INDEX IF NOT EXISTS propietarios_lots_property_name_trgm_idx ON propietarios_lots USING gin (lower(property_name) gin_trgm_ops) WHERE archived_at IS NULL;

COMMIT;
