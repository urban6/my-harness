CREATE TABLE products (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    price    BIGINT       NOT NULL CHECK (price > 0),
    stock    INTEGER      NOT NULL CHECK (stock >= 0),
    reserved INTEGER      NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    CONSTRAINT products_reserved_le_stock CHECK (reserved <= stock)
);

CREATE TABLE coupons (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(64)  NOT NULL,
    type                VARCHAR(10)  NOT NULL,
    value               BIGINT       NOT NULL CHECK (value > 0),
    min_order_amount    BIGINT       NOT NULL DEFAULT 0 CHECK (min_order_amount >= 0),
    max_discount_amount BIGINT       CHECK (max_discount_amount >= 0),
    total_quantity      INTEGER      NOT NULL CHECK (total_quantity > 0),
    used_count          INTEGER      NOT NULL DEFAULT 0,
    valid_from          TIMESTAMPTZ  NOT NULL,
    valid_until         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT coupons_code_uq UNIQUE (code),
    CONSTRAINT coupons_used_range CHECK (used_count >= 0 AND used_count <= total_quantity)
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(100) NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    coupon_code VARCHAR(64),
    subtotal    BIGINT       NOT NULL,
    discount    BIGINT       NOT NULL,
    total_price BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    paid_at     TIMESTAMPTZ,
    payment_id  VARCHAR(100)
);
CREATE INDEX orders_user_idx ON orders (user_id, id DESC);
CREATE INDEX orders_pending_expiry_idx ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';

CREATE TABLE order_items (
    order_id   BIGINT  NOT NULL REFERENCES orders (id),
    line_no    INTEGER NOT NULL,
    product_id BIGINT  NOT NULL REFERENCES products (id),
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    unit_price BIGINT  NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

CREATE TABLE idempotency_keys (
    id           BIGSERIAL PRIMARY KEY,
    scope        VARCHAR(160) NOT NULL,
    idem_key     VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64)  NOT NULL,
    order_id     BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT idempotency_scope_key_uq UNIQUE (scope, idem_key)
);
