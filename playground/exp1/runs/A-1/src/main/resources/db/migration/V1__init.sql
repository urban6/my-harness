CREATE TABLE products (
    id    BIGSERIAL PRIMARY KEY,
    name  VARCHAR(255) NOT NULL,
    price BIGINT       NOT NULL CHECK (price > 0),
    stock INTEGER      NOT NULL CHECK (stock >= 0)
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    status      VARCHAR(20)              NOT NULL,
    total_price BIGINT                   NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_orders_created_at_id ON orders (created_at DESC, id DESC);

CREATE TABLE order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT  NOT NULL REFERENCES orders (id),
    product_id BIGINT  NOT NULL REFERENCES products (id),
    quantity   INTEGER NOT NULL CHECK (quantity >= 1),
    unit_price BIGINT  NOT NULL,
    UNIQUE (order_id, product_id)
);
