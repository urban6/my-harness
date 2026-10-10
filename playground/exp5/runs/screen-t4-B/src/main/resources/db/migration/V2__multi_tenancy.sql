-- Multi-tenancy: every product, coupon, order and idempotency key belongs to a tenant.
-- Existing data is not migrated (the database starts empty), hence NOT NULL without a default.

ALTER TABLE products ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
ALTER TABLE coupons ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
ALTER TABLE orders ADD COLUMN tenant_id VARCHAR(30) NOT NULL;
ALTER TABLE idempotency_keys ADD COLUMN tenant_id VARCHAR(30) NOT NULL;

-- coupon code is unique per tenant only
ALTER TABLE orders DROP CONSTRAINT orders_coupon_code_fkey;
ALTER TABLE coupons DROP CONSTRAINT coupons_pkey;
ALTER TABLE coupons ADD PRIMARY KEY (tenant_id, code);
ALTER TABLE orders ADD CONSTRAINT orders_coupon_fkey
    FOREIGN KEY (tenant_id, coupon_code) REFERENCES coupons (tenant_id, code);

-- idempotency key space is per tenant
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (tenant_id, scope, idem_key);

DROP INDEX orders_created_idx;
DROP INDEX orders_user_created_idx;
DROP INDEX orders_coupon_user_idx;
CREATE INDEX orders_tenant_created_idx ON orders (tenant_id, created_at DESC, id DESC);
CREATE INDEX orders_tenant_user_created_idx ON orders (tenant_id, user_id, created_at DESC, id DESC);
CREATE INDEX orders_tenant_coupon_user_idx ON orders (tenant_id, coupon_code, user_id);
