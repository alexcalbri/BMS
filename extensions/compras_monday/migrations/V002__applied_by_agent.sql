BEGIN;

-- Nombre del agente de IA que confirmo la importacion (monday_apply_import), para que el registro de actividad de la empresa
-- diga Agente de <usuario> en cada fila importada. applied_by sigue guardando solo al usuario; NULL = la confirmo una persona
-- en la pantalla (o la importacion es anterior a esta columna).
ALTER TABLE ext_compras_monday__jobs ADD COLUMN IF NOT EXISTS applied_by_agent TEXT;

COMMIT;
