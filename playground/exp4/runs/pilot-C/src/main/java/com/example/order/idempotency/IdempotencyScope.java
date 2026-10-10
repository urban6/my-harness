package com.example.order.idempotency;

/** 엔드포인트별 독립 키 공간. */
public enum IdempotencyScope {
    ORDER_CREATE, ORDER_PAY
}
