create table products (
    id        bigserial primary key,
    name      varchar(255) not null,
    price     bigint       not null check (price > 0),
    stock     integer      not null check (stock >= 0),
    reserved  integer      not null default 0,
    constraint chk_products_reserved check (reserved >= 0 and reserved <= stock)
);

create table coupons (
    id                  bigserial primary key,
    code                varchar(64) not null unique,
    type                varchar(16) not null check (type in ('FIXED', 'RATE')),
    value               bigint      not null check (value > 0),
    min_order_amount    bigint check (min_order_amount >= 0),
    max_discount_amount bigint check (max_discount_amount > 0),
    total_quantity      integer     not null check (total_quantity > 0),
    used_count          integer     not null default 0,
    valid_from          timestamptz not null,
    valid_until         timestamptz not null,
    constraint chk_coupons_used check (used_count >= 0 and used_count <= total_quantity)
);

create table orders (
    id                  bigserial primary key,
    user_id             varchar(128) not null,
    idempotency_key     varchar(255) not null,
    request_fingerprint text         not null,
    status              varchar(32)  not null,
    coupon_code         varchar(64),
    subtotal            bigint       not null,
    discount            bigint       not null,
    total_price         bigint       not null,
    created_at          timestamptz  not null,
    expires_at          timestamptz  not null,
    paid_at             timestamptz,
    payment_id          varchar(128),
    pay_idempotency_key varchar(255),
    constraint uq_orders_user_idempotency unique (user_id, idempotency_key)
);

create index idx_orders_user on orders (user_id);
create index idx_orders_status_id on orders (status, id);
create index idx_orders_pending_expiry on orders (expires_at) where status = 'PENDING_PAYMENT';

create table order_items (
    order_id   bigint  not null references orders (id),
    line_no    integer not null,
    product_id bigint  not null references products (id),
    quantity   integer not null check (quantity > 0),
    unit_price bigint  not null,
    primary key (order_id, line_no)
);
