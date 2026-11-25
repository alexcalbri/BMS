BEGIN;

-- Configuracion del envio a monday por tipo de registro (hoy solo lead). Sin fila = apagado: nada se envia hasta que
-- alguien lo activa y elige el tablero. Las columnas mapeadas se guardan como un objeto JSON: campo -> {id de la columna, tipo de monday}.
CREATE TABLE ext_monday__outbound (
    record_type VARCHAR(63) PRIMARY KEY CHECK (record_type ~ '^[a-z][a-z0-9_]{0,62}$'),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    board_id VARCHAR(20) CHECK (board_id ~ '^[0-9]{1,20}$'),
    group_id VARCHAR(100),
    send_created BOOLEAN NOT NULL DEFAULT TRUE,
    send_updated BOOLEAN NOT NULL DEFAULT TRUE,
    send_notes BOOLEAN NOT NULL DEFAULT TRUE,
    send_files BOOLEAN NOT NULL DEFAULT TRUE,
    send_imports BOOLEAN NOT NULL DEFAULT FALSE,
    columns JSONB NOT NULL DEFAULT '{}'::jsonb,
    files_column VARCHAR(100),
    key_column VARCHAR(100),
    max_file_mb INTEGER NOT NULL DEFAULT 25 CHECK (max_file_mb BETWEEN 1 AND 500),
    phone_country CHAR(2),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
