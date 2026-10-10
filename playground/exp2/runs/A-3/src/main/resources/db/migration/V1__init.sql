CREATE TABLE products (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    price      BIGINT       NOT NULL,
    stock      INTEGER      NOT NULL,
    reserved   INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_products_price CHECK (price > 0),
    CONSTRAINT chk_products_stock CHECK (stock >= 0),
    CONSTRAINT chk_products_reserved CHECK (reserved >= 0 AND reserved <= stock)
);

CREATE TABLE coupons (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(20)  NOT NULL,
    type                VARCHAR(10)  NOT NULL,
    value               BIGINT       NOT NULL,
    min_order_amount    BIGINT       NOT NULL,
    max_discount_amount BIGINT,
    total_quantity      INTEGER      NOT NULL,
    used_count          INTEGER      NOT NULL DEFAULT 0,
    valid_from          TIMESTAMPTZ  NOT NULL,
    valid_until         TIMESTAMPTZ  NOT NULL,
    valid_from_text     VARCHAR(100) NOT NULL,
    valid_until_text    VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_coupons_code UNIQUE (code),
    CONSTRAINT chk_coupons_used CHECK (used_count >= 0 AND used_count <= total_quantity)
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(50)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    coupon_code VARCHAR(20) REFERENCES coupons (code),
    subtotal    BIGINT       NOT NULL,
    discount    BIGINT       NOT NULL,
    total_price BIGINT       NOT NULL,
    payment_id  VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    paid_at     TIMESTAMPTZ
);

CREATE INDEX idx_orders_created ON orders (created_at DESC, id DESC);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC, id DESC);
CREATE INDEX idx_orders_status_created ON orders (status, created_at DESC, id DESC);
CREATE INDEX idx_orders_pending_expiry ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';
-- 한 사용자는 같은 쿠폰을 동시에 하나의 주문에서만 사용할 수 있다 (R2.5 의 최종 방어선)
CREATE UNIQUE INDEX uq_orders_active_coupon_per_user ON orders (user_id, coupon_code)
    WHERE coupon_code IS NOT NULL AND status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED');

CREATE TABLE order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT  NOT NULL REFERENCES orders (id),
    line_no    INTEGER NOT NULL,
    product_id BIGINT  NOT NULL REFERENCES products (id),
    quantity   INTEGER NOT NULL,
    unit_price BIGINT  NOT NULL,
    CONSTRAINT uq_order_items_line UNIQUE (order_id, line_no)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE idempotency_records (
    scope             VARCHAR(20)  NOT NULL,
    idem_key          VARCHAR(64)  NOT NULL,
    fingerprint       VARCHAR(64)  NOT NULL,
    state             VARCHAR(20)  NOT NULL,
    response_status   INTEGER,
    response_body     TEXT,
    response_location VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, idem_key)
);
