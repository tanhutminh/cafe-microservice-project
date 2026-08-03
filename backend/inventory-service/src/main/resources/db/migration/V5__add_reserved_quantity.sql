-- Soft-reservation stock model: verifying an order holds quantity here without
-- touching current_stock; paying converts the hold into a real deduction. Zero
-- default keeps existing rows fully available until something reserves against them.
ALTER TABLE ingredients ADD COLUMN reserved_quantity NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0);
