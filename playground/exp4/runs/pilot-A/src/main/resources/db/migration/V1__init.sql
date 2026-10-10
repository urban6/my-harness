create table products (
    id       bigserial primary key,
    name     varchar(100) not null,
    price    bigint       not null check (price > 0),
    stock    bigint       not null check (stock >= 0),
    reserved bigint       not null check (reserved >= 0),
    constraint chk_products_reserved check (reserved <= stock)
);

create table coupons (
    id                  bigserial primary key,
    code                varchar(20)  not null unique,
    type                varchar(10)  not null,
    value               bigint       not null,
    min_order_amount    bigint       not null,
    max_discount_amount bigint,
    total_quantity      bigint       not null,
    used_count          bigint       not null,
    valid_from          timestamptz  not null,
    valid_until         timestamptz  not null,
    constraint chk_coupons_used check (used_count >= 0 and used_count <= total_quantity)
);

create table orders (
    id                 bigserial primary key,
    user_id            varchar(50)  not null,
    status             varchar(20)  not null,
    coupon_id          bigint references coupons (id),
    coupon_code        varchar(20),
    subtotal           bigint       not null,
    discount           bigint       not null,
    total_price        bigint       not null,
    created_at         timestamptz  not null,
    expires_at         timestamptz  not null,
    paid_at            timestamptz,
    payment_id         varchar(200),
    gateway_started_at timestamptz
);

create index idx_orders_list on orders (created_at desc, id desc);
create index idx_orders_user on orders (user_id);
create index idx_orders_expiry on orders (expires_at) where status = 'PENDING_PAYMENT';
create index idx_orders_coupon_user on orders (coupon_id, user_id);

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
    fingerprint       varchar(64) not null,
    state             varchar(12) not null,
    response_status   integer,
    response_body     text,
    response_location text,
    created_at        timestamptz not null,
    primary key (scope, idem_key)
);
