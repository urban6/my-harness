CREATE TABLE product (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    price      BIGINT       NOT NULL CHECK (price > 0),
    stock      INTEGER      NOT NULL CHECK (stock >= 0),
    reserved   INTEGER      NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= stock),
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE coupon (
    code                VARCHAR(20) PRIMARY KEY,
    type                VARCHAR(10) NOT NULL,
    value               BIGINT      NOT NULL,
    min_order_amount    BIGINT      NOT NULL,
    max_discount_amount BIGINT,
    total_quantity      INTEGER     NOT NULL,
    used_count          INTEGER     NOT NULL DEFAULT 0 CHECK (used_count >= 0 AND used_count <= total_quantity),
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_until         TIMESTAMPTZ NOT NULL
);

CREATE TABLE orders (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              VARCHAR(50) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    coupon_code          VARCHAR(20) REFERENCES coupon (code),
    subtotal             BIGINT      NOT NULL,
    discount             BIGINT      NOT NULL,
    total_price          BIGINT      NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    paid_at              TIMESTAMPTZ,
    payment_id           VARCHAR(100),
    pending_operation    VARCHAR(20),
    pending_operation_at TIMESTAMPTZ
);

CREATE INDEX idx_orders_created ON orders (created_at DESC, id DESC);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC, id DESC);
CREATE INDEX idx_orders_pending_expiry ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';

-- 한 사용자는 같은 쿠폰을 동시에 한 주문에서만 사용할 수 있다 (R2.5 안전망)
CREATE UNIQUE INDEX uq_orders_active_coupon_per_user ON orders (user_id, coupon_code)
    WHERE coupon_code IS NOT NULL AND status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED');

CREATE TABLE order_item (
    order_id   BIGINT  NOT NULL REFERENCES orders (id),
    line_no    INTEGER NOT NULL,
    product_id BIGINT  NOT NULL REFERENCES product (id),
    quantity   INTEGER NOT NULL,
    unit_price BIGINT  NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

CREATE TABLE idempotency_record (
    scope           VARCHAR(20)  NOT NULL,
    idem_key        VARCHAR(64)  NOT NULL,
    fingerprint     VARCHAR(64)  NOT NULL,
    state           VARCHAR(20)  NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    location        VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope, idem_key)
);
