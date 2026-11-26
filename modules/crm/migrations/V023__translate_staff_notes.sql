BEGIN;

-- CRM: interruptor "Traducir notas del personal" (I18N.md §8). Apagado por defecto: las notas que escribe el personal solo se
-- envian al proveedor de traduccion cuando la empresa lo activa. Los nombres (etapas, tableros) no dependen de este ajuste.
ALTER TABLE crm_settings ADD COLUMN IF NOT EXISTS translate_staff_notes BOOLEAN NOT NULL DEFAULT FALSE;

COMMIT;
