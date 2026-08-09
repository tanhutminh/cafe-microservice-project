CREATE TABLE dining_tables (
    id           BIGSERIAL PRIMARY KEY,
    table_number VARCHAR(20) NOT NULL UNIQUE,
    capacity     INT         NOT NULL DEFAULT 4,
    status       VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    active       BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE orders (
    id             BIGSERIAL PRIMARY KEY,
    table_id       BIGINT      NOT NULL REFERENCES dining_tables (id),
    status         VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    payment_method VARCHAR(30),
    failure_reason VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at      TIMESTAMPTZ
);

CREATE INDEX idx_orders_table_id ON orders (table_id);

CREATE TABLE order_items (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT        NOT NULL REFERENCES orders (id),
    menu_item_id    BIGINT        NOT NULL,
    name_snapshot   VARCHAR(150)  NOT NULL,
    price_snapshot  NUMERIC(10,2) NOT NULL CHECK (price_snapshot >= 0),
    quantity        INT           NOT NULL CHECK (quantity > 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);

-- Saga orchestration bookkeeping for checkout. Not exposed via API;
-- the order's own status/failure_reason columns are what the POS UI polls.
CREATE TABLE order_saga_state (
    order_id     BIGINT      PRIMARY KEY REFERENCES orders (id),
    step         VARCHAR(30) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
