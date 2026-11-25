BEGIN;

-- Cola de salida (envio a servicios externos): un aviso por cambio de un registro y por servicio (sink).
-- payload es el OutboundEvent serializado; los datos del registro se leen al enviarlo, no se guardan aqui.
CREATE TABLE IF NOT EXISTS outbound_events (
    id BIGSERIAL PRIMARY KEY,
    sink_id VARCHAR(63) NOT NULL CHECK (sink_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    module_id VARCHAR(63) NOT NULL CHECK (module_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    record_type VARCHAR(63) NOT NULL CHECK (record_type ~ '^[a-z][a-z0-9_]{0,62}$'),
    record_id VARCHAR(100) NOT NULL CHECK (length(record_id) > 0),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('created', 'updated', 'note', 'file')),
    origin VARCHAR(80) NOT NULL DEFAULT 'user' CHECK (length(origin) > 0),
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sending', 'sent', 'failed', 'skipped')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT CHECK (last_error IS NULL OR length(last_error) <= 2000),
    result TEXT CHECK (result IS NULL OR length(result) <= 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    locked_at TIMESTAMPTZ,

    CONSTRAINT outbound_events_locked_check CHECK (status <> 'sending' OR locked_at IS NOT NULL)
);

-- Vencidos de un servicio, en orden.
CREATE INDEX IF NOT EXISTS outbound_events_due_idx
    ON outbound_events (sink_id, next_attempt_at, id) WHERE status = 'pending';

-- Aviso anterior no terminado de un registro (conserva el orden por registro).
CREATE INDEX IF NOT EXISTS outbound_events_head_idx
    ON outbound_events (sink_id, record_type, record_id, id) WHERE status IN ('pending', 'sending', 'failed');

-- Pendientes por servicio en orden de id (sirve al ORDER BY de la reclamacion).
CREATE INDEX IF NOT EXISTS outbound_events_pending_id_idx
    ON outbound_events (sink_id, id) WHERE status = 'pending';

-- Bloqueos viejos que hay que devolver a pendiente.
CREATE INDEX IF NOT EXISTS outbound_events_stale_idx
    ON outbound_events (locked_at) WHERE status = 'sending';

-- Los mas recientes de un servicio, sin importar el estado.
CREATE INDEX IF NOT EXISTS outbound_events_sink_idx
    ON outbound_events (sink_id, id DESC);

-- Listado de estado por servicio.
CREATE INDEX IF NOT EXISTS outbound_events_status_idx
    ON outbound_events (sink_id, status, id DESC);

-- Purga de lo ya entregado u omitido.
CREATE INDEX IF NOT EXISTS outbound_events_purge_idx
    ON outbound_events (updated_at) WHERE status IN ('sent', 'skipped');

-- Elemento de fuera que corresponde a cada registro, por servicio.
CREATE TABLE IF NOT EXISTS outbound_links (
    sink_id VARCHAR(63) NOT NULL CHECK (sink_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    record_type VARCHAR(63) NOT NULL CHECK (record_type ~ '^[a-z][a-z0-9_]{0,62}$'),
    record_id VARCHAR(100) NOT NULL CHECK (length(record_id) > 0),
    external_id VARCHAR(200) NOT NULL CHECK (length(external_id) > 0),
    container VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (sink_id, record_type, record_id)
);

-- Conexion de la empresa con un servicio externo (p. ej. "monday"), compartida por las extensiones.
-- secret va sellado (enc:v1:...); nunca se guarda en claro.
CREATE TABLE IF NOT EXISTS integration_connections (
    service VARCHAR(63) PRIMARY KEY CHECK (service ~ '^[a-z][a-z0-9_]{0,62}$'),
    secret TEXT NOT NULL CHECK (length(secret) > 0),
    updated_by TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
