BEGIN;

-- Nombre del agente de IA que inicio la importacion (monday_read_board), para que el registro de actividad de la empresa diga
-- Agente de <usuario> en cada fila importada. created_by sigue guardando solo al usuario; NULL = la inicio una persona en la
-- pantalla (o la importacion es anterior a esta columna).
ALTER TABLE ext_propietarios_monday__jobs ADD COLUMN IF NOT EXISTS created_by_agent TEXT;

COMMIT;
