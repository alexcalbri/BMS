BEGIN;

-- Puesta al dia del esquema de cada extension en la empresa (EXTENSIONS.md, Actualizar un paquete).
-- tenant_extensions.version es la version cuyas migraciones ya se aplicaron en esta empresa: el Core no deja correr el
-- codigo de otra version hasta aplicarlas. Si fallan, la extension queda suspendida (status incompatible): schema_error
-- guarda el motivo que ve el business owner y status_before_error el estado al que vuelve cuando se aplican.
ALTER TABLE tenant_extensions ADD COLUMN IF NOT EXISTS schema_error TEXT;
ALTER TABLE tenant_extensions ADD COLUMN IF NOT EXISTS status_before_error VARCHAR(16);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint WHERE conname = 'tenant_extensions_status_before_error_check'
                   AND conrelid = 'public.tenant_extensions'::regclass) THEN
        ALTER TABLE tenant_extensions ADD CONSTRAINT tenant_extensions_status_before_error_check
            CHECK (status_before_error IS NULL OR status_before_error IN ('installed', 'enabled', 'disabled'));
    END IF;
END
$$;

COMMIT;
