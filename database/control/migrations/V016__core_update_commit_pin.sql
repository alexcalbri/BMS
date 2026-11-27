BEGIN;

-- Commit exacto (40 hex) que aprobo el dueno del servidor en la app. El ayudante root instala ese commit
-- y ningun otro (SIGNING.md). signature_status lo escribe solo el ayudante: 'verified' (firma SSH valida
-- de la llave instalada) o 'unsigned' (no hay llave instalada).
ALTER TABLE core_update_requests
    ADD COLUMN IF NOT EXISTS target_commit TEXT
        CHECK (target_commit IS NULL OR target_commit ~ '^[0-9a-f]{40}$'),
    ADD COLUMN IF NOT EXISTS signature_status TEXT
        CHECK (signature_status IS NULL OR signature_status IN ('verified', 'unsigned'));

-- El rol del backend ya puede insertar target_version: recibe tambien target_commit, sin esperar a que
-- install.sh/update.sh repitan sus GRANT. Ningun otro privilegio cambia.
DO $$
DECLARE
    grantee_name name;
BEGIN
    FOR grantee_name IN
        SELECT DISTINCT roles.rolname
        FROM pg_attribute attributes
        CROSS JOIN LATERAL aclexplode(attributes.attacl) acl
        JOIN pg_roles roles ON roles.oid = acl.grantee
        WHERE attributes.attrelid = 'public.core_update_requests'::regclass
          AND attributes.attname = 'target_version'
          AND acl.privilege_type = 'INSERT'
    LOOP
        EXECUTE format('GRANT INSERT (target_commit) ON public.core_update_requests TO %I', grantee_name);
    END LOOP;
END $$;

COMMIT;
