CREATE TABLE categories (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    display_order INT          NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE menu_items (
    id          BIGSERIAL PRIMARY KEY,
    category_id BIGINT        NOT NULL REFERENCES categories (id),
    name        VARCHAR(150)  NOT NULL,
    description VARCHAR(500),
    price       NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    image_url   VARCHAR(500),
    available   BOOLEAN       NOT NULL DEFAULT TRUE,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_menu_items_category_id ON menu_items (category_id);
CREATE INDEX idx_menu_items_available ON menu_items (available) WHERE active;
