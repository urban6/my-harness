create table products (
    id       bigserial primary key,
    name     varchar(255) not null,
    price    bigint       not null,
    stock    integer      not null,
    reserved integer      not null default 0,
    constraint ck_products_price check (price >= 0),
    constraint ck_products_stock check (reserved >= 0 and reserved <= stock)
);

create table coupons (
    id                  bigserial primary key,
    code                varchar(100) not null,
    type                varchar(20)  not null,
    value               bigint       not null,
    min_order_amount    bigint       not null,
    max_discount_amount bigint,
    total_quantity      integer      not null,
    used_count          integer      not null default 0,
    valid_from          timestamptz  not null,
    valid_until         timestamptz  not null,
    constraint uq_coupons_code unique (code),
    constraint ck_coupons_used check (used_count >= 0 and used_count <= total_quantity)
);

create table orders (
    id                 bigserial primary key,
    user_id            bigint       not null,
    status             varchar(20)  not null,
    idempotency_key    varchar(255) not null,
    request_hash       varchar(64)  not null,
    coupon_code        varchar(100),
    subtotal           bigint       not null,
    discount           bigint       not null,
    total_price        bigint       not null,
    created_at         timestamptz  not null,
    expires_at         timestamptz  not null,
    paid_at            timestamptz,
    payment_key        varchar(255),
    payment_id         varchar(255),
    gateway_started_at timestamptz,
    constraint uq_orders_idempotency unique (user_id, idempotency_key)
);

create index ix_orders_user_id on orders (user_id, id desc);
create index ix_orders_status_id on orders (status, id desc);
create index ix_orders_pending_expiry on orders (expires_at) where status = 'PENDING_PAYMENT';

create table order_items (
    order_id   bigint  not null references orders (id),
    product_id bigint  not null references products (id),
    quantity   integer not null,
    unit_price bigint  not null,
    primary key (order_id, product_id)
);
