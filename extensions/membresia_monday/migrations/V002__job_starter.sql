BEGIN;

-- Quien empezo la importacion (usuario de la empresa): el registro de actividad atribuye a esa persona cada fila importada.
-- created_by sigue guardando solo el nombre que se muestra; las importaciones anteriores quedan sin usuario.
ALTER TABLE ext_membresia_monday__jobs ADD COLUMN IF NOT EXISTS created_by_user UUID;

COMMIT;
