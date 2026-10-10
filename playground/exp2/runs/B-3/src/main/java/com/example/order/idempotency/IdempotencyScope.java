package com.example.order.idempotency;

/** 멱등 키 공간. 엔드포인트마다 독립이다. */
public enum IdempotencyScope {
    ORDER_CREATE,
    ORDER_PAY
}
