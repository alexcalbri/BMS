BEGIN;

-- Indices de rendimiento (pasada de rendimiento 1).
-- Ingresos: revenueRows filtra "status <> 'cancelada' AND starts_on BETWEEN ..." (predicado identico al del indice).
CREATE INDEX IF NOT EXISTS membresia_memberships_revenue_idx
    ON membresia_memberships (starts_on) WHERE status <> 'cancelada';

-- El inicio (countsByStatus de activas y por vencer) usa el indice existente membresia_memberships_ends_idx (ends_on, id).

COMMIT;
