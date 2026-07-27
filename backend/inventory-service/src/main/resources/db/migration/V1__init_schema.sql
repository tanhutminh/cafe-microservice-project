CREATE TABLE ingredients (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100)  NOT NULL,
    unit          VARCHAR(20)   NOT NULL,
    current_stock NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK (current_stock >= 0),
    active        BOOLEAN       NOT NULL DEFAULT TRUE
);

-- menu_item_id is a loose application-level reference to menu-service's MenuItem — no real FK
-- is possible across service databases (plan section 8).
CREATE TABLE menu_item_ingredients (
    id                BIGSERIAL PRIMARY KEY,
    menu_item_id      BIGINT        NOT NULL,
    ingredient_id     BIGINT        NOT NULL REFERENCES ingredients (id),
    quantity_required NUMERIC(12,3) NOT NULL CHECK (quantity_required > 0),
    UNIQUE (menu_item_id, ingredient_id)
);

CREATE INDEX idx_menu_item_ingredients_menu_item_id ON menu_item_ingredients (menu_item_id);

CREATE TABLE stock_movements (
    id            BIGSERIAL PRIMARY KEY,
    ingredient_id BIGINT        NOT NULL REFERENCES ingredients (id),
    change_amount NUMERIC(12,3) NOT NULL,
    reason        VARCHAR(50)   NOT NULL,
    reference_id  VARCHAR(100),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_movements_ingredient_id ON stock_movements (ingredient_id);

-- Idempotency for the reserve-stock saga command (plan section 4): Kafka is at-least-once,
-- so a redelivered command must not double-deduct stock. Stores the outcome so a duplicate
-- command can be answered with the same reply instead of recomputing.
CREATE TABLE processed_saga_steps (
    order_id     BIGINT      PRIMARY KEY,
    step         VARCHAR(30) NOT NULL,
    success      BOOLEAN     NOT NULL,
    reason       VARCHAR(500),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
