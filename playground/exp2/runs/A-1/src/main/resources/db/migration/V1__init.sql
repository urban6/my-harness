create table products (
    id         bigserial primary key,
    name       varchar(100) not null,
    price      bigint       not null,
    stock      integer      not null check (stock >= 0),
    reserved   integer      not null default 0 check (reserved >= 0 and reserved <= stock),
    created_at timestamptz  not null
);

create table coupons (
    code                varchar(20) primary key,
    type                varchar(10) not null,
    value               bigint      not null,
    min_order_amount    bigint      not null,
    max_discount_amount bigint,
    total_quantity      integer     not null,
    used_count          integer     not null default 0 check (used_count >= 0 and used_count <= total_quantity),
    valid_from          timestamptz not null,
    valid_until         timestamptz not null,
    created_at          timestamptz not null
);

create table orders (
    id          bigserial primary key,
    user_id     varchar(50)  not null,
    status      varchar(20)  not null,
    coupon_code varchar(20) references coupons (code),
    subtotal    bigint       not null,
    discount    bigint       not null,
    total_price bigint       not null,
    created_at  timestamptz  not null,
    expires_at  timestamptz  not null,
    paid_at     timestamptz,
    payment_id  varchar(100)
);

create index idx_orders_created on orders (created_at desc, id desc);
create index idx_orders_user_created on orders (user_id, created_at desc, id desc);
create index idx_orders_pending_expiry on orders (expires_at) where status = 'PENDING_PAYMENT';
-- 한 사용자는 같은 쿠폰을 사용 중인 주문을 하나만 가질 수 있다 (R2.5 안전망)
create unique index uq_orders_active_coupon_per_user on orders (user_id, coupon_code)
    where coupon_code is not null and status in ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED');

create table order_items (
    id         bigserial primary key,
    order_id   bigint  not null references orders (id),
    product_id bigint  not null references products (id),
    quantity   integer not null,
    unit_price bigint  not null
);

create index idx_order_items_order on order_items (order_id);

create table idempotency_keys (
    scope             varchar(20) not null,
    idem_key          varchar(64) not null,
    request_hash      varchar(64) not null,
    status            varchar(20) not null,
    response_status   integer,
    response_body     text,
    response_location varchar(255),
    created_at        timestamptz not null,
    primary key (scope, idem_key)
);
