BEGIN;

-- Quien empezo la importacion, como lo guarda el registro de actividad (JSON de ActivityActor: persona, agente de IA, dueno o
-- administrador del servidor desde el servidor). Cada fila importada se atribuye a esa misma identidad. NULL = importaciones
-- anteriores: se reconstruye la persona desde created_by como antes.
ALTER TABLE crm_import_jobs ADD COLUMN IF NOT EXISTS started_actor TEXT;

COMMIT;
