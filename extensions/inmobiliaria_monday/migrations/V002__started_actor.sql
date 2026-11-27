BEGIN;

-- Quien pulso Aplicar, como lo guarda el registro de actividad (JSON de ActivityActor: persona, agente de IA, dueno o
-- administrador del servidor desde el servidor). Cada fila importada se atribuye a esa misma identidad. NULL = importaciones
-- anteriores: se reconstruye la persona desde applied_by como antes.
ALTER TABLE ext_inmobiliaria_monday__jobs ADD COLUMN IF NOT EXISTS started_actor TEXT;

COMMIT;
