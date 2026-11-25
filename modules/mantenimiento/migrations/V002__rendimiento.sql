BEGIN;

-- Búsquedas por texto parcial (lower(col) LIKE '%texto%') con índices de trigramas.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Columnas de estados abiertos y cerrados del tablero y estado cerrado elegido: WHERE status = ? ORDER BY updated_at DESC, number DESC.
CREATE INDEX IF NOT EXISTS mantenimiento_orders_status_updated_idx ON mantenimiento_orders (status, updated_at DESC, number DESC) WHERE archived_at IS NULL;
-- Vista "Cerradas": WHERE status IN ('done', 'rejected', 'cancelled') AND updated_at >= ? ORDER BY updated_at DESC, number DESC.
CREATE INDEX IF NOT EXISTS mantenimiento_orders_closed_idx ON mantenimiento_orders (updated_at DESC, number DESC) WHERE archived_at IS NULL AND status IN ('done', 'rejected', 'cancelled');
-- Historial de órdenes de un plan: WHERE plan_id = ? ORDER BY created_at DESC, number DESC.
CREATE INDEX IF NOT EXISTS mantenimiento_orders_plan_idx ON mantenimiento_orders (plan_id, created_at DESC, number DESC) WHERE plan_id IS NOT NULL AND archived_at IS NULL;

-- Búsqueda de órdenes: lower(title) LIKE ? OR lower(description) LIKE ? (siempre con archived_at IS NULL).
CREATE INDEX IF NOT EXISTS mantenimiento_orders_title_trgm_idx ON mantenimiento_orders USING gin (lower(title) gin_trgm_ops) WHERE archived_at IS NULL;
CREATE INDEX IF NOT EXISTS mantenimiento_orders_description_trgm_idx ON mantenimiento_orders USING gin (lower(description) gin_trgm_ops) WHERE archived_at IS NULL;

-- Búsqueda de activos: lower(name) LIKE ? OR lower(category) LIKE ? OR lower(location) LIKE ? (con NOT archived).
CREATE INDEX IF NOT EXISTS mantenimiento_assets_name_trgm_idx ON mantenimiento_assets USING gin (lower(name) gin_trgm_ops) WHERE NOT archived;
CREATE INDEX IF NOT EXISTS mantenimiento_assets_category_trgm_idx ON mantenimiento_assets USING gin (lower(category) gin_trgm_ops) WHERE NOT archived;
CREATE INDEX IF NOT EXISTS mantenimiento_assets_location_trgm_idx ON mantenimiento_assets USING gin (lower(location) gin_trgm_ops) WHERE NOT archived;

-- tareas-sync: intentos fallidos por orden. Las que fallan se excluyen del lote con espera creciente y, tras el tope (10), hasta que
-- un cambio de la orden vuelva a marcarla (eso reinicia el contador).
ALTER TABLE mantenimiento_orders
    ADD COLUMN IF NOT EXISTS tareas_sync_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tareas_sync_last_attempt_at TIMESTAMPTZ NULL;

-- Lote de tareas-sync: WHERE tareas_sync_needed AND archived_at IS NULL AND tareas_sync_attempts < 10 ... ORDER BY updated_at, id LIMIT ?
CREATE INDEX IF NOT EXISTS mantenimiento_orders_sync_retry_idx ON mantenimiento_orders (updated_at, id) WHERE tareas_sync_needed AND archived_at IS NULL AND tareas_sync_attempts < 10;

COMMIT;
