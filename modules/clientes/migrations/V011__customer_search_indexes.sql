BEGIN;

-- Paginacion por cursor de la lista de clientes: orden y cursor por (lower(display_name), id).
-- Usa el operador de comparacion normal, por eso el indice es de clase por defecto (el de V001 no lleva id).
CREATE INDEX IF NOT EXISTS customers_name_cursor_idx
    ON customers (lower(display_name), id);

-- Busqueda por prefijo (LIKE 'texto%'): con una collation que no es C solo la usan los indices text_pattern_ops.
-- No se usa pg_trgm: los roles de empresa no son superusuario y las migraciones no crean extensiones.
CREATE INDEX IF NOT EXISTS customers_name_prefix_idx
    ON customers (lower(display_name) text_pattern_ops);

CREATE INDEX IF NOT EXISTS customers_email_prefix_idx
    ON customers (lower(primary_email) text_pattern_ops);

CREATE INDEX IF NOT EXISTS customers_phone_prefix_idx
    ON customers (primary_phone text_pattern_ops);

COMMIT;
