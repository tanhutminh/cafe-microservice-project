-- When the order's table was released (NULL if it hasn't been released since this order
-- started). Distinct from the order's own terminal status (PAID/CANCELLED): paying doesn't
-- release the table by itself (pay-first-then-dine), so a settled order can sit with a closed
-- status for a while before its table - and this column - are released.
ALTER TABLE orders ADD COLUMN released_at TIMESTAMPTZ;

-- Backfill: mark every order released, except the single most recent
-- non-cancelled order on each table that's currently OCCUPIED - the same
-- row the live current-order lookup already returns for it, so nothing
-- changes for that table at the moment this migration runs. A cancelled
-- order releases its table as part of cancelling itself, so closed_at is
-- already an accurate historical release time for those rows - everything
-- else (no such synchronous link between closing and releasing) falls back
-- to the migration's own run time, the closest available approximation.
UPDATE orders o
SET released_at = CASE
    WHEN o.status = 'CANCELLED' THEN COALESCE(o.closed_at, now())
    ELSE now()
    END
WHERE NOT EXISTS (
    SELECT 1
    FROM (
        SELECT DISTINCT ON (o2.table_id) o2.id
        FROM orders o2
                 JOIN dining_tables t ON t.id = o2.table_id
        WHERE t.status = 'OCCUPIED'
          AND o2.status <> 'CANCELLED'
        ORDER BY o2.table_id, o2.created_at DESC, o2.id DESC
    ) keep
    WHERE keep.id = o.id
);
