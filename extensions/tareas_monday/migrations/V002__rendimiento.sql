BEGIN;

-- Tamano de pagina de la lectura (baja a la mitad si monday se queja de complejidad) y relecturas por cursor vencido.
ALTER TABLE ext_tareas_monday__jobs ADD COLUMN IF NOT EXISTS page_size INTEGER NOT NULL DEFAULT 25;
ALTER TABLE ext_tareas_monday__jobs ADD COLUMN IF NOT EXISTS restarts INTEGER NOT NULL DEFAULT 0;

-- Filas por trabajar (sin aplicar o con archivos por bajar): la consulta de pendientes del trabajo de importacion.
CREATE INDEX IF NOT EXISTS ext_tareas_monday__rows_pending_idx ON ext_tareas_monday__rows (job_id, row_no)
    WHERE outcome IS NULL OR files_pending;

COMMIT;
