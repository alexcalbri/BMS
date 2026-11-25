BEGIN;

-- "Mis solicitudes abiertas" del inicio: requester_id = ? AND status NOT IN (...), el mismo predicado que
-- ComprasVisibility.openStatusesSql.
CREATE INDEX IF NOT EXISTS compras_requests_requester_open_idx ON compras_requests (requester_id, status)
    WHERE status NOT IN ('completed', 'cancelled', 'rejected');

-- Limpieza de adjuntos: intentos fallidos de borrado; con el tope (ComprasAttachments.MaxCleanupAttempts = 5) se omiten.
ALTER TABLE compras_attachments ADD COLUMN IF NOT EXISTS cleanup_attempts SMALLINT NOT NULL DEFAULT 0;

-- El indice de V001 se recrea sin las filas atascadas; la consulta de cleanup lleva literalmente `cleanup_attempts < 5`.
DROP INDEX IF EXISTS compras_attachments_cleanup_idx;
CREATE INDEX compras_attachments_cleanup_idx ON compras_attachments (created_at)
    WHERE (deleted_at IS NOT NULL OR request_id IS NULL) AND cleanup_attempts < 5;

COMMIT;
