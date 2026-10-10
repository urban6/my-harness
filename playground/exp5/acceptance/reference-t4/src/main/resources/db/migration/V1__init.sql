CREATE TABLE products (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT        NOT NULL,
    price      BIGINT      NOT NULL,
    stock      BIGINT      NOT NULL,
    reserved   BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT products_stock_nonneg CHECK (stock >= 0),
    CONSTRAINT products_reserved_nonneg CHECK (reserved >= 0)
);

CREATE TABLE coupons (
    code                VARCHAR(20) PRIMARY KEY,
    type                VARCHAR(10) NOT NULL,
    value               BIGINT      NOT NULL,
    min_order_amount    BIGINT      NOT NULL,
    max_discount_amount BIGINT,
    total_quantity      BIGINT      NOT NULL,
    used_count          BIGINT      NOT NULL DEFAULT 0,
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_until         TIMESTAMPTZ NOT NULL,
    CONSTRAINT coupons_used_range CHECK (used_count >= 0 AND used_count <= total_quantity)
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    user_id     TEXT        NOT NULL,
    status      VARCHAR(20) NOT NULL,
    coupon_code VARCHAR(20) REFERENCES coupons (code),
    subtotal    BIGINT      NOT NULL,
    discount    BIGINT      NOT NULL,
    total_price BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    paid_at     TIMESTAMPTZ,
    payment_id  TEXT
);

CREATE INDEX orders_created_idx ON orders (created_at DESC, id DESC);
CREATE INDEX orders_user_created_idx ON orders (user_id, created_at DESC, id DESC);
CREATE INDEX orders_pending_expiry_idx ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX orders_coupon_user_idx ON orders (coupon_code, user_id);

CREATE TABLE order_items (
    order_id   BIGINT  NOT NULL REFERENCES orders (id),
    line_no    INTEGER NOT NULL,
    product_id BIGINT  NOT NULL REFERENCES products (id),
    quantity   BIGINT  NOT NULL,
    unit_price BIGINT  NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

CREATE TABLE idempotency_keys (
    scope           VARCHAR(20) NOT NULL,
    idem_key        TEXT        NOT NULL,
    fingerprint     TEXT        NOT NULL,
    state           VARCHAR(12) NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    location        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, idem_key)
);
