package com.example.order.idempotency;

/** 엔드포인트별로 독립된 멱등 키 공간(R4.1). */
public enum IdempotencyScope {
    CREATE_ORDER,
    PAY_ORDER
}
