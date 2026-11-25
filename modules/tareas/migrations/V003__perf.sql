BEGIN;

-- Rendimiento. La columna Hecha del kanban muestra las ultimas cerradas: el indice parcial sigue el filtro
-- (tablero, status = 'done', no archivada) y el orden (completed_at DESC, id DESC) de TareasRepository.list.
-- Las canceladas no tienen completed_at (tareas_tasks_done_check) y ordenan por updated_at sin indice propio
-- (no se indexa updated_at para no perder las actualizaciones HOT): les sirve tareas_tasks_board_status_idx.
CREATE INDEX IF NOT EXISTS tareas_tasks_done_idx
    ON tareas_tasks (board_id, completed_at DESC, id DESC)
    WHERE archived_at IS NULL AND status = 'done';

-- Intentos fallidos de borrar el archivo de un adjunto: pasado el tope la limpieza deja de reintentarlo
-- y no retiene al resto del lote.
ALTER TABLE tareas_attachments ADD COLUMN IF NOT EXISTS delete_attempts INTEGER NOT NULL DEFAULT 0;

COMMIT;
