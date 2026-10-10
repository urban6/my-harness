-- 멀티테넌시(M1~M5): 모든 데이터는 tenant_id로 나뉜다. 빈 DB에서 시작하므로 기존 행 이전은 없다.

-- 상품
ALTER TABLE products ADD COLUMN tenant_id VARCHAR(30) NOT NULL;

-- 쿠폰: code는 테넌트 안에서만 유일하다 (M3.1)
ALTER TABLE orders DROP CONSTRAINT orders_coupon_code_fkey;
ALTER TABLE coupons ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
ALTER TABLE coupons DROP CONSTRAINT coupons_pkey;
ALTER TABLE coupons ADD PRIMARY KEY (tenant_id, code);

-- 주문: 쿠폰 참조도 같은 테넌트 안에서만 성립한다 (M2.3, M3.2)
ALTER TABLE orders ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
ALTER TABLE orders ADD CONSTRAINT orders_coupon_fk FOREIGN KEY (tenant_id, coupon_code)
    REFERENCES coupons (tenant_id, code);
DROP INDEX orders_created_idx;
DROP INDEX orders_user_created_idx;
DROP INDEX orders_coupon_user_idx;
CREATE INDEX orders_tenant_created_idx ON orders (tenant_id, created_at DESC, id DESC);
CREATE INDEX orders_tenant_user_created_idx ON orders (tenant_id, user_id, created_at DESC, id DESC);
CREATE INDEX orders_tenant_coupon_user_idx ON orders (tenant_id, coupon_code, user_id);

-- 멱등 키: 키 공간이 테넌트별이다 (M5.1)
ALTER TABLE idempotency_keys ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (tenant_id, scope, idem_key);
