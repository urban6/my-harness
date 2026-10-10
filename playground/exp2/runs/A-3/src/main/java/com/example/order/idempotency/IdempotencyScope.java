package com.example.order.idempotency;

/** 주문 생성과 결제는 서로 독립된 키 공간을 쓴다 (R4.1). */
public enum IdempotencyScope {
    CREATE_ORDER,
    PAY_ORDER
}
