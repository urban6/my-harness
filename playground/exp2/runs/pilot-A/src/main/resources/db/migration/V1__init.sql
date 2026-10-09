CREATE TABLE products (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    price      BIGINT       NOT NULL CHECK (price > 0),
    stock      BIGINT       NOT NULL CHECK (stock >= 0),
    reserved   BIGINT       NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= stock),
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE coupons (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(20)  NOT NULL UNIQUE,
    type                VARCHAR(10)  NOT NULL,
    value               BIGINT       NOT NULL,
    min_order_amount    BIGINT       NOT NULL,
    max_discount_amount BIGINT,
    total_quantity      BIGINT       NOT NULL,
    used_count          BIGINT       NOT NULL DEFAULT 0 CHECK (used_count >= 0 AND used_count <= total_quantity),
    valid_from          TIMESTAMPTZ  NOT NULL,
    valid_from_offset   INT          NOT NULL,
    valid_until         TIMESTAMPTZ  NOT NULL,
    valid_until_offset  INT          NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(50)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    coupon_code VARCHAR(20),
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
CREATE INDEX idx_orders_pending_expiry ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';

-- 한 사용자는 같은 쿠폰을 "사용 중"인 주문을 최대 1건만 가질 수 있다 (R2.5).
CREATE UNIQUE INDEX uq_orders_active_coupon_per_user ON orders (user_id, coupon_code)
    WHERE coupon_code IS NOT NULL AND status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED');

CREATE TABLE order_items (
    order_id   BIGINT NOT NULL REFERENCES orders (id),
    line_no    INT    NOT NULL,
    product_id BIGINT NOT NULL REFERENCES products (id),
    quantity   INT    NOT NULL,
    unit_price BIGINT NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

CREATE TABLE idempotency_records (
    scope           VARCHAR(20)  NOT NULL,
    idem_key        VARCHAR(64)  NOT NULL,
    fingerprint     VARCHAR(64)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    response_status INT,
    response_body   TEXT,
    location        TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope, idem_key)
);
