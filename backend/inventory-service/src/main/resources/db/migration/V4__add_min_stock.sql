-- Low-stock alert threshold per ingredient (M3). Zero default keeps existing
-- rows behaving exactly as before (never "low stock") until an admin sets a
-- real threshold.
ALTER TABLE ingredients ADD COLUMN min_stock NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (min_stock >= 0);
