-- Multi-tenancy (change request M1-M8). Existing data is not migrated: the columns are NOT NULL on an empty DB.

-- products: ids stay globally unique, every row belongs to one tenant
ALTER TABLE products ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
CREATE INDEX products_tenant_idx ON products (tenant_id, id);

-- coupons: code is unique per tenant only (M3.1)
ALTER TABLE orders DROP CONSTRAINT orders_coupon_code_fkey;
ALTER TABLE coupons DROP CONSTRAINT coupons_pkey;
ALTER TABLE coupons ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
ALTER TABLE coupons ADD CONSTRAINT coupons_pkey PRIMARY KEY (tenant_id, code);

-- orders: belong to a tenant; the coupon reference is (tenant_id, coupon_code)
ALTER TABLE orders ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
ALTER TABLE orders ADD CONSTRAINT orders_coupon_fkey
    FOREIGN KEY (tenant_id, coupon_code) REFERENCES coupons (tenant_id, code);

DROP INDEX orders_created_idx;
DROP INDEX orders_user_created_idx;
DROP INDEX orders_coupon_user_idx;
CREATE INDEX orders_tenant_created_idx ON orders (tenant_id, created_at DESC, id DESC);
CREATE INDEX orders_tenant_user_created_idx ON orders (tenant_id, user_id, created_at DESC, id DESC);
CREATE INDEX orders_tenant_coupon_user_idx ON orders (tenant_id, coupon_code, user_id);

-- idempotency keys: key space per tenant and per endpoint (M5.1, R4.1)
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_pkey PRIMARY KEY (tenant_id, scope, idem_key);
