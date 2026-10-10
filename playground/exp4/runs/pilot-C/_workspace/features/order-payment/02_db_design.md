# 02. DB 설계 — order-payment

- PostgreSQL + Flyway. 응답 필드 ↔ 컬럼 대응표와 제약 ↔ HTTP 상태 매핑표는 복제하지 않고 `01_api_design.md` 끝의 **정합 요약**을 기준으로 한다.
- 마이그레이션 파일: `src/main/resources/db/migration/V1__init_schema.sql` (기존 `.gitkeep` 옆에 추가). 실행은 앱 기동 시 Flyway, 수동 실행 금지(구현 단계 담당).
- 트랜잭션 격리: 기본 READ COMMITTED 유지. 정합은 행 락(`FOR UPDATE`) + CHECK/UNIQUE 제약으로 보장한다.

## 1. 테이블 개요

| 테이블 | 용도 |
|---|---|
| `products` | 상품, `stock`/`reserved` |
| `coupons` | 쿠폰, `used_count` |
| `orders` | 주문 (상태, 금액, 만료, 결제 id, PG 진행 표식) |
| `order_items` | 주문 항목 (가격 스냅샷) |
| `idempotency_records` | 주문 생성/결제 멱등 키 |

## 2. 컬럼·제약

### products
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGSERIAL | PK |
| name | VARCHAR(100) | NOT NULL |
| price | BIGINT | NOT NULL, `chk_products_price` CHECK (price BETWEEN 1 AND 10000000) |
| stock | INTEGER | NOT NULL, `chk_products_stock` CHECK (stock >= 0) |
| reserved | INTEGER | NOT NULL DEFAULT 0, `chk_products_reserved` CHECK (reserved >= 0 AND reserved <= stock) |
| created_at | TIMESTAMPTZ | NOT NULL |

### coupons
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGSERIAL | PK |
| code | VARCHAR(20) | NOT NULL, `uk_coupons_code` UNIQUE, `chk_coupons_code` CHECK (code ~ '^[A-Z0-9]{4,20}$') |
| type | VARCHAR(10) | NOT NULL, `chk_coupons_type` CHECK (type IN ('FIXED','RATE')) |
| value | BIGINT | NOT NULL, `chk_coupons_value` CHECK ((type='FIXED' AND value >= 1) OR (type='RATE' AND value BETWEEN 1 AND 100)) |
| min_order_amount | BIGINT | NOT NULL DEFAULT 0, CHECK (>= 0) |
| max_discount_amount | BIGINT | NULL, CHECK (NULL 또는 >= 1) |
| total_quantity | INTEGER | NOT NULL, CHECK (>= 1) |
| used_count | INTEGER | NOT NULL DEFAULT 0, `chk_coupons_used_count` CHECK (used_count >= 0 AND used_count <= total_quantity) |
| valid_from | TIMESTAMPTZ | NOT NULL |
| valid_until | TIMESTAMPTZ | NOT NULL, `chk_coupons_period` CHECK (valid_from < valid_until) |
| created_at | TIMESTAMPTZ | NOT NULL |

### orders
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGSERIAL | PK |
| user_id | VARCHAR(50) | NOT NULL |
| status | VARCHAR(20) | NOT NULL, `chk_orders_status` CHECK IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED','PAYMENT_FAILED','EXPIRED','CANCELLED','REFUNDED') |
| coupon_id | BIGINT | NULL, FK → coupons(id) |
| coupon_code | VARCHAR(20) | NULL (응답용 비정규화; 목록 조회 시 조인 회피) |
| subtotal | BIGINT | NOT NULL, CHECK (>= 0) |
| discount | BIGINT | NOT NULL, CHECK (discount >= 0 AND discount <= subtotal) |
| total_price | BIGINT | NOT NULL, `chk_orders_total` CHECK (total_price = subtotal - discount) |
| created_at | TIMESTAMPTZ | NOT NULL (앱에서 µs 절단) |
| expires_at | TIMESTAMPTZ | NOT NULL, CHECK (expires_at >= created_at) |
| paid_at | TIMESTAMPTZ | NULL |
| payment_id | VARCHAR(100) | NULL — PG 환불용. 0원 주문은 NULL |
| gateway_inflight_since | TIMESTAMPTZ | NULL — PG 결제/환불 호출 선점 표식(트랜잭션 밖 호출 중임을 표시). 10초 지나면 무효 |
| updated_at | TIMESTAMPTZ | NOT NULL |
| (추가 CHECK) | | `chk_orders_coupon_pair` CHECK ((coupon_id IS NULL) = (coupon_code IS NULL)) |

- 한 사용자-쿠폰 동시 사용 1건 보증(핵심): **부분 유니크 인덱스** `uk_orders_user_coupon_in_use` ON orders(user_id, coupon_id) WHERE coupon_id IS NOT NULL AND status IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED'). 상태가 REFUNDED/CANCELLED/EXPIRED/PAYMENT_FAILED로 바뀌면 인덱스 대상에서 빠져 같은 사용자가 재사용 가능(R2.6). 상태 문자열이 이 집합이어야 한다 — "사용 중" 정의(01 문서 §1.5)와 정확히 일치.

### order_items
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGSERIAL | PK |
| order_id | BIGINT | NOT NULL, FK → orders(id) |
| product_id | BIGINT | NOT NULL, FK → products(id) |
| quantity | INTEGER | NOT NULL, CHECK (quantity BETWEEN 1 AND 1000) |
| unit_price | BIGINT | NOT NULL, CHECK (unit_price >= 1) |
| line_no | INTEGER | NOT NULL — 요청 항목 순서(응답 `items` 순서) |
| | | `uk_order_items_order_product` UNIQUE (order_id, product_id) |

### idempotency_records
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGSERIAL | PK |
| scope | VARCHAR(20) | NOT NULL, CHECK IN ('ORDER_CREATE','ORDER_PAY') — 엔드포인트별 독립 키 공간 |
| idem_key | VARCHAR(64) | NOT NULL |
| user_id | VARCHAR(50) | NULL (결제는 X-User-Id 선택) |
| request_path | VARCHAR(100) | NOT NULL (예 `/api/orders`, `/api/orders/42/pay`) |
| body_hash | VARCHAR(64) | NOT NULL — 정규화 본문의 SHA-256 hex (cardToken 원문은 저장하지 않음) |
| status | VARCHAR(12) | NOT NULL, CHECK IN ('IN_PROGRESS','COMPLETED') |
| response_status | INTEGER | NULL, CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 299) |
| response_body | TEXT | NULL — 최초 응답 JSON 문자열(재생용) |
| response_location | VARCHAR(255) | NULL — 경로만(예 `/api/orders/7`), 재생 시 절대 URL로 변환 |
| created_at | TIMESTAMPTZ | NOT NULL |
| completed_at | TIMESTAMPTZ | NULL |
| | | `uk_idempotency_scope_key` UNIQUE (scope, idem_key) |
| | | `chk_idempotency_completed` CHECK (status <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL)) |

- 유니크는 `(scope, idem_key)`만: 사용자·경로는 키 공간에 포함하지 않고 같은 키의 다른 사용자/경로/본문 요청은 지문 비교로 422 처리하기 위함(R4.3).
- 비밀번호/토큰성 값: `cardToken`은 어떤 테이블에도 저장하지 않는다(멱등 지문은 해시만).

## 3. 인덱스

| 이름 | 테이블 | 정의 | 용도 |
|---|---|---|---|
| (PK) | 각 테이블 | id | |
| `uk_coupons_code` | coupons | UNIQUE(code) | code 조회·중복 방지 |
| `uk_orders_user_coupon_in_use` | orders | UNIQUE(user_id, coupon_id) WHERE coupon_id IS NOT NULL AND status IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED') | 사용자-쿠폰 동시 사용 1건 보증, "사용 중" 조회 |
| `idx_orders_created_id` | orders | (created_at DESC, id DESC) | 필터 없는 목록 keyset |
| `idx_orders_user_created` | orders | (user_id, created_at DESC, id DESC) | userId 필터 목록 |
| `idx_orders_status_created` | orders | (status, created_at DESC, id DESC) | status 필터 목록 |
| `idx_orders_pending_expiry` | orders | (expires_at) WHERE status = 'PENDING_PAYMENT' | 만료 스캔 |
| `idx_orders_coupon_id` | orders | (coupon_id) | FK |
| `idx_order_items_product_id` | order_items | (product_id) | FK |
| `uk_order_items_order_product` | order_items | UNIQUE(order_id, product_id) | 중복 방지 + order_id 조회 |
| `uk_idempotency_scope_key` | idempotency_records | UNIQUE(scope, idem_key) | 키 선점(유니크 기반 claim) |

(`userId`+`status` 동시 필터는 `idx_orders_user_created`로 스캔 후 status 필터. 데이터 규모상 충분.)

## 4. 핵심 쿼리·락 규약 (구현 지침)

전역 락 순서: **orders 행 → products 행(id 오름차순) → coupons 행**.

```sql
-- 상품 잠금 (id 오름차순; JPQL: @Lock(PESSIMISTIC_WRITE) ... ORDER BY p.id)
SELECT * FROM products WHERE id IN (:ids) ORDER BY id FOR UPDATE;

-- 쿠폰 잠금
SELECT * FROM coupons WHERE code = :code FOR UPDATE;

-- 주문 잠금
SELECT * FROM orders WHERE id = :id FOR UPDATE;

-- 사용 중 확인 (쿠폰 락 획득 후)
SELECT EXISTS (SELECT 1 FROM orders WHERE user_id = :userId AND coupon_id = :couponId
               AND status IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED'));

-- 멱등 claim
INSERT INTO idempotency_records (scope, idem_key, user_id, request_path, body_hash, status, created_at)
VALUES (:scope, :key, :userId, :path, :hash, 'IN_PROGRESS', :now)
ON CONFLICT (scope, idem_key) DO NOTHING;           -- 영향 행 1 = 선점 성공

-- 멱등 완료 (비즈니스 트랜잭션 안)
UPDATE idempotency_records SET status='COMPLETED', response_status=:s, response_body=:b,
       response_location=:loc, completed_at=:now WHERE id=:id AND status='IN_PROGRESS';

-- 멱등 해제 (오류 시, 새 트랜잭션)
DELETE FROM idempotency_records WHERE id = :id AND status = 'IN_PROGRESS';

-- 만료 대상 스캔 (락 없음)
SELECT id FROM orders
 WHERE status = 'PENDING_PAYMENT' AND expires_at <= :now
   AND (gateway_inflight_since IS NULL OR gateway_inflight_since < :nowMinus10s)
 ORDER BY expires_at LIMIT 100;

-- 목록 keyset (필터는 조건부 조합; 아래는 모두 지정한 경우)
SELECT * FROM orders
 WHERE user_id = :userId AND status = :status
   AND (created_at, id) < (:cursorCreatedAt, :cursorId)
 ORDER BY created_at DESC, id DESC
 LIMIT :size + 1;
```
- 상품/쿠폰 수량 변경은 락 보유 상태에서 엔티티 더티체킹 또는 `UPDATE products SET reserved = reserved + :q WHERE id = :id`(동일 효과)로 수행. `@Version`(낙관적 락) 사용하지 않음.
- 쿠폰 복원: `used_count = used_count - 1` (락 보유, `chk_coupons_used_count`가 음수 방지).
- 락 대기 중 연결 점유가 길어지지 않도록 Hikari `maximum-pool-size: 20`. PG HTTP 호출은 트랜잭션·커넥션 밖(01 문서 §3.2).
- 엔티티 매핑: `Instant` ↔ `TIMESTAMPTZ`, enum은 `@Enumerated(EnumType.STRING)`, `ddl-auto: validate`(스키마는 Flyway 단독 소유).

## 5. 마이그레이션 초안 — `V1__init_schema.sql`

```sql
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
```

## 6. 설계 결정 메모 (한 줄 근거)

- 금액 컬럼 전부 BIGINT: C1(int 초과 가능); 수량류는 INTEGER(범위가 작음).
- `orders.coupon_code` 비정규화: 쿠폰은 수정·삭제 불가(범위 제외)이므로 불일치 위험이 없고 목록 조회에서 조인을 없앤다.
- `order_items.unit_price` 스냅샷: 이후 상품 가격이 바뀌어도 주문 금액 불변(R3.5).
- `payment_id` 주문 저장: R7.3 PG 환불 경로 `/v1/payments/{paymentId}/refund`에 필요.
- `gateway_inflight_since`: PG 호출을 트랜잭션 밖에서 하면서 같은 주문의 PG 호출 1회 + 만료/취소와의 이중 처리를 막는 선점 표식(01 문서 §3.2). 별도 테이블 대신 주문 행 컬럼 → 주문 락 하나로 일관 처리.
- 멱등 `response_body`를 문자열(TEXT)로 저장: JSONB 재직렬화로 키 순서·숫자 표현이 바뀌어 "같은 본문" 재생이 깨지는 것을 방지.
- 락 방식: 상품은 `FOR UPDATE`(id 오름차순) 후 검사 → 404 → 409(재고) → 409(쿠폰) 순서를 정확히 보고하고 다중 상품 데드락을 제거.
- DB CHECK(`reserved <= stock`, `used_count <= total_quantity`)는 앱 로직이 어긋나도 과예약·초과 사용을 막는 최후 방어선.
- Flyway 단일 마이그레이션 V1: 신규 서비스(그린필드)라 분할 불필요.
