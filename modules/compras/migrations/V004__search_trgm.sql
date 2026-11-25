BEGIN;

-- Busqueda por texto: indice trigram sobre titulo + solicitante. La expresion es IDENTICA a
-- ComprasVisibility.searchSql (la busqueda usa `<expresion> LIKE lower(?)`); si una cambia, cambia la otra.
-- pg_trgm es una extension confiable (PG13+): la crea el dueno de la base.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS compras_requests_search_trgm_idx ON compras_requests
    USING gin ((lower(coalesce(title, '') || ' ' || coalesce(requester_name, ''))) gin_trgm_ops);

COMMIT;
