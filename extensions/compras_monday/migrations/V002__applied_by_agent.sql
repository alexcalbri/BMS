BEGIN;

-- Nombre del agente de IA que confirmo la importacion (monday_apply_import), para que el registro de actividad de la empresa
-- diga Agente de <usuario> en cada fila importada. applied_by sigue guardando solo al usuario; NULL = la confirmo una persona
-- en la pantalla (o la importacion es anterior a esta columna).
ALTER TABLE ext_compras_monday__jobs ADD COLUMN IF NOT EXISTS applied_by_agent TEXT;

-- Quien empezo la importacion, como lo guarda el registro de actividad (JSON de ActivityActor: persona, agente de IA, dueno o
-- administrador del servidor desde el servidor). Cada fila importada se atribuye a esa misma identidad. NULL = importaciones
-- anteriores: se reconstruye como antes.
ALTER TABLE ext_compras_monday__jobs ADD COLUMN IF NOT EXISTS started_actor TEXT;

COMMIT;
