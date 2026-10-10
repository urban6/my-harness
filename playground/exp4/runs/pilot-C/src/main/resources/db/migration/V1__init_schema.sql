CREATE TABLE products (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    price       BIGINT       NOT NULL,
    stock       INTEGER      NOT NULL,
    reserved    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_products_price    CHECK (price BETWEEN 1 AND 10000000),
    CONSTRAINT chk_products_stock    CHECK (stock >= 0),
    CONSTRAINT chk_products_reserved CHECK (reserved >= 0 AND reserved <= stock)
);

CREATE TABLE coupons (
    id                  BIGSERIAL    PRIMARY KEY,
    code                VARCHAR(20)  NOT NULL,
    type                VARCHAR(10)  NOT NULL,
    value               BIGINT       NOT NULL,
    min_order_amount    BIGINT       NOT NULL DEFAULT 0,
    max_discount_amount BIGINT,
    total_quantity      INTEGER      NOT NULL,
    used_count          INTEGER      NOT NULL DEFAULT 0,
    valid_from          TIMESTAMPTZ  NOT NULL,
    valid_until         TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_coupons_code        UNIQUE (code),
    CONSTRAINT chk_coupons_code       CHECK (code ~ '^[A-Z0-9]{4,20}$'),
    CONSTRAINT chk_coupons_type       CHECK (type IN ('FIXED', 'RATE')),
    CONSTRAINT chk_coupons_value      CHECK ((type = 'FIXED' AND value >= 1)
                                          OR (type = 'RATE' AND value BETWEEN 1 AND 100)),
    CONSTRAINT chk_coupons_min_order  CHECK (min_order_amount >= 0),
    CONSTRAINT chk_coupons_max_disc   CHECK (max_discount_amount IS NULL OR max_discount_amount >= 1),
    CONSTRAINT chk_coupons_total      CHECK (total_quantity >= 1),
    CONSTRAINT chk_coupons_used_count CHECK (used_count >= 0 AND used_count <= total_quantity),
    CONSTRAINT chk_coupons_period     CHECK (valid_from < valid_until)
);

CREATE TABLE orders (
    id                     BIGSERIAL    PRIMARY KEY,
    user_id                VARCHAR(50)  NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    coupon_id              BIGINT       REFERENCES coupons (id),
    coupon_code            VARCHAR(20),
    subtotal               BIGINT       NOT NULL,
    discount               BIGINT       NOT NULL,
    total_price            BIGINT       NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL,
    expires_at             TIMESTAMPTZ  NOT NULL,
    paid_at                TIMESTAMPTZ,
    payment_id             VARCHAR(100),
    gateway_inflight_since TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_orders_status      CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED',
                                                        'PAYMENT_FAILED', 'EXPIRED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT chk_orders_amounts     CHECK (subtotal >= 0 AND discount >= 0 AND discount <= subtotal),
    CONSTRAINT chk_orders_total       CHECK (total_price = subtotal - discount),
    CONSTRAINT chk_orders_expiry      CHECK (expires_at >= created_at),
    CONSTRAINT chk_orders_coupon_pair CHECK ((coupon_id IS NULL) = (coupon_code IS NULL))
);

-- 한 사용자가 같은 쿠폰을 "사용 중"(PENDING_PAYMENT/PAID/SHIPPED/DELIVERED)인 주문을 2건 이상 가질 수 없다.
CREATE UNIQUE INDEX uk_orders_user_coupon_in_use
    ON orders (user_id, coupon_id)
    WHERE coupon_id IS NOT NULL
      AND status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED');

CREATE INDEX idx_orders_created_id      ON orders (created_at DESC, id DESC);
CREATE INDEX idx_orders_user_created    ON orders (user_id, created_at DESC, id DESC);
CREATE INDEX idx_orders_status_created  ON orders (status, created_at DESC, id DESC);
CREATE INDEX idx_orders_pending_expiry  ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_orders_coupon_id       ON orders (coupon_id);

CREATE TABLE order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT    NOT NULL REFERENCES orders (id),
    product_id BIGINT    NOT NULL REFERENCES products (id),
    quantity   INTEGER   NOT NULL,
    unit_price BIGINT    NOT NULL,
    line_no    INTEGER   NOT NULL,
    CONSTRAINT uk_order_items_order_product UNIQUE (order_id, product_id),
    CONSTRAINT chk_order_items_quantity     CHECK (quantity BETWEEN 1 AND 1000),
    CONSTRAINT chk_order_items_unit_price   CHECK (unit_price >= 1)
);
CREATE INDEX idx_order_items_product_id ON order_items (product_id);

CREATE TABLE idempotency_records (
    id                BIGSERIAL    PRIMARY KEY,
    scope             VARCHAR(20)  NOT NULL,
    idem_key          VARCHAR(64)  NOT NULL,
    user_id           VARCHAR(50),
    request_path      VARCHAR(100) NOT NULL,
    body_hash         VARCHAR(64)  NOT NULL,
    status            VARCHAR(12)  NOT NULL,
    response_status   INTEGER,
    response_body     TEXT,
    response_location VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL,
    completed_at      TIMESTAMPTZ,
    CONSTRAINT uk_idempotency_scope_key   UNIQUE (scope, idem_key),
    CONSTRAINT chk_idempotency_scope      CHECK (scope IN ('ORDER_CREATE', 'ORDER_PAY')),
    CONSTRAINT chk_idempotency_status     CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_idempotency_resp_code  CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 299),
    CONSTRAINT chk_idempotency_completed  CHECK (status <> 'COMPLETED'
                                                 OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);
