BEGIN;

-- Estado de la lectura del tablero: tamano de pagina vigente (baja ante errores de complejidad de monday) y relecturas
-- desde cero por cursor vencido (vuelve a 0 cuando una pagina se lee bien).
ALTER TABLE ext_empleados_monday__jobs ADD COLUMN IF NOT EXISTS page_size INTEGER NOT NULL DEFAULT 25;
ALTER TABLE ext_empleados_monday__jobs ADD COLUMN IF NOT EXISTS restarts INTEGER NOT NULL DEFAULT 0;

-- Filas por trabajar (sin aplicar): sirve exactamente la consulta de pendientes del runner
-- (WHERE job_id = ? AND outcome IS NULL ORDER BY row_no LIMIT ?), sin recorrer las filas ya aplicadas.
CREATE INDEX IF NOT EXISTS ext_empleados_monday__rows_pending_idx ON ext_empleados_monday__rows (job_id, row_no)
    WHERE outcome IS NULL;

COMMIT;
