CREATE TABLE products (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    price    BIGINT       NOT NULL CHECK (price >= 0),
    stock    INT          NOT NULL CHECK (stock >= 0),
    reserved INT          NOT NULL DEFAULT 0,
    CHECK (reserved >= 0 AND reserved <= stock)
);

CREATE TABLE coupons (
    code                VARCHAR(100) PRIMARY KEY,
    type                VARCHAR(10)  NOT NULL,
    value               BIGINT       NOT NULL CHECK (value > 0),
    min_order_amount    BIGINT       NOT NULL DEFAULT 0,
    max_discount_amount BIGINT,
    total_quantity      INT          NOT NULL CHECK (total_quantity > 0),
    used_count          INT          NOT NULL DEFAULT 0,
    valid_from          TIMESTAMPTZ  NOT NULL,
    valid_until         TIMESTAMPTZ  NOT NULL,
    CHECK (used_count >= 0 AND used_count <= total_quantity)
);

CREATE TABLE orders (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(100) NOT NULL,
    status              VARCHAR(30)  NOT NULL,
    coupon_code         VARCHAR(100),
    subtotal            BIGINT       NOT NULL,
    discount            BIGINT       NOT NULL,
    total_price         BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    paid_at             TIMESTAMPTZ,
    idempotency_key     VARCHAR(255) NOT NULL,
    request_hash        VARCHAR(2000) NOT NULL,
    pay_idempotency_key VARCHAR(255),
    payment_id          VARCHAR(255),
    payment_started_at  TIMESTAMPTZ,
    CONSTRAINT uq_orders_user_key UNIQUE (user_id, idempotency_key)
);
CREATE INDEX idx_orders_user ON orders (user_id, id DESC);
CREATE INDEX idx_orders_pending_expiry ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';

CREATE TABLE order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES orders (id),
    product_id BIGINT NOT NULL REFERENCES products (id),
    quantity   INT    NOT NULL CHECK (quantity > 0),
    unit_price BIGINT NOT NULL
);
CREATE INDEX idx_order_items_order ON order_items (order_id);
