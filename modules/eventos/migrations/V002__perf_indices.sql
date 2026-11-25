BEGIN;

-- Índices de rendimiento (0.2.1). Cada uno coincide con una consulta de EventosRepository.

-- listEvents "Pasados": ORDER BY e.starts_at DESC, e.id LIMIT n ("Próximos" ya usa eventos_events_starts_idx de V001).
CREATE INDEX IF NOT EXISTS eventos_events_starts_desc_id_idx ON eventos_events (starts_at DESC, id);

-- overview: inscritos desde el lunes (status = 'inscrito' AND created_at >= ?).
CREATE INDEX IF NOT EXISTS eventos_registrations_inscrito_created_idx ON eventos_registrations (created_at) WHERE status = 'inscrito';

COMMIT;
