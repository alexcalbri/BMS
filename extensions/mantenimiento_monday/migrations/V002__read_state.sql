BEGIN;

-- Estado de la lectura del tablero: tamano de pagina vigente (baja ante errores de complejidad de monday) y relecturas
-- desde cero por cursor vencido (vuelve a 0 cuando una pagina se lee bien).
ALTER TABLE ext_mantenimiento_monday__jobs ADD COLUMN IF NOT EXISTS page_size INTEGER NOT NULL DEFAULT 25;
ALTER TABLE ext_mantenimiento_monday__jobs ADD COLUMN IF NOT EXISTS restarts INTEGER NOT NULL DEFAULT 0;

COMMIT;
