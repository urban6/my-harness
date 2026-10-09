create table products (
    id         bigserial primary key,
    name       varchar(100) not null,
    price      bigint       not null check (price > 0),
    stock      integer      not null check (stock >= 0),
    reserved   integer      not null default 0 check (reserved >= 0),
    created_at timestamptz  not null,
    constraint products_reserved_le_stock check (reserved <= stock)
);

create table coupons (
    id                  bigserial primary key,
    code                varchar(20) not null unique,
    type                varchar(10) not null check (type in ('FIXED', 'RATE')),
    value               bigint      not null check (value > 0),
    min_order_amount    bigint      not null check (min_order_amount >= 0),
    max_discount_amount bigint check (max_discount_amount > 0),
    total_quantity      integer     not null check (total_quantity > 0),
    used_count          integer     not null default 0,
    valid_from          timestamptz not null,
    valid_until         timestamptz not null,
    created_at          timestamptz not null,
    constraint coupons_used_count_range check (used_count between 0 and total_quantity),
    constraint coupons_valid_period check (valid_from < valid_until)
);

create table orders (
    id          bigserial primary key,
    user_id     varchar(50) not null,
    status      varchar(20) not null check (status in ('PENDING_PAYMENT', 'PAID', 'PAYMENT_FAILED', 'EXPIRED',
                                                       'CANCELLED', 'REFUNDED', 'SHIPPED', 'DELIVERED')),
    coupon_code varchar(20) references coupons (code),
    subtotal    bigint      not null check (subtotal > 0),
    discount    bigint      not null check (discount >= 0),
    total_price bigint      not null check (total_price >= 0),
    payment_id  varchar(100),
    created_at  timestamptz not null,
    expires_at  timestamptz not null,
    paid_at     timestamptz
);

-- 목록 조회(R9): createdAt desc, id desc 키셋 페이지네이션
create index orders_created_at_id_idx on orders (created_at desc, id desc);
create index orders_user_created_at_id_idx on orders (user_id, created_at desc, id desc);
-- 결제 만료 스윕(R6)
create index orders_pending_expires_at_idx on orders (expires_at) where status = 'PENDING_PAYMENT';
-- 한 사용자는 같은 쿠폰을 사용 중인 주문을 하나만 가질 수 있다(R2.5) — 애플리케이션 검사의 안전망
create unique index orders_active_coupon_per_user_uk on orders (user_id, coupon_code)
    where coupon_code is not null and status in ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED');

create table order_items (
    order_id   bigint  not null references orders (id),
    line_no    integer not null,
    product_id bigint  not null references products (id),
    quantity   integer not null check (quantity > 0),
    unit_price bigint  not null check (unit_price > 0),
    primary key (order_id, line_no)
);

create table idempotency_keys (
    scope             varchar(30)  not null,
    idempotency_key   varchar(64)  not null,
    request_hash      varchar(64)  not null,
    status            varchar(20)  not null check (status in ('IN_PROGRESS', 'COMPLETED')),
    response_status   integer,
    response_body     text,
    response_location varchar(500),
    created_at        timestamptz  not null default now(),
    primary key (scope, idempotency_key)
);
