CREATE TABLE products (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    price    BIGINT       NOT NULL CHECK (price >= 0),
    stock    INTEGER      NOT NULL CHECK (stock >= 0),
    reserved INTEGER      NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    CHECK (reserved <= stock)
);

CREATE TABLE coupons (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(100) NOT NULL UNIQUE,
    type                VARCHAR(20)  NOT NULL,
    value               BIGINT       NOT NULL,
    min_order_amount    BIGINT       NOT NULL DEFAULT 0,
    max_discount_amount BIGINT,
    total_quantity      INTEGER      NOT NULL,
    used_count          INTEGER      NOT NULL DEFAULT 0,
    valid_from          TIMESTAMPTZ  NOT NULL,
    valid_until         TIMESTAMPTZ  NOT NULL,
    CHECK (used_count >= 0 AND used_count <= total_quantity)
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(100) NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    coupon_code VARCHAR(100),
    subtotal    BIGINT       NOT NULL,
    discount    BIGINT       NOT NULL,
    total_price BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    paid_at     TIMESTAMPTZ,
    payment_id  VARCHAR(100),
    payment_key VARCHAR(200)
);
CREATE INDEX idx_orders_user ON orders (user_id, id DESC);
CREATE INDEX idx_orders_pending_expiry ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';

CREATE TABLE order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT  NOT NULL REFERENCES orders (id),
    product_id BIGINT  NOT NULL REFERENCES products (id),
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    unit_price BIGINT  NOT NULL
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE idempotency_keys (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(100) NOT NULL,
    idem_key    VARCHAR(200) NOT NULL,
    fingerprint TEXT         NOT NULL,
    order_id    BIGINT,
    UNIQUE (user_id, idem_key)
);
