package com.example.order.ordering;

import java.util.List;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED,
    SHIPPED,
    DELIVERED,
    REFUNDED;

    /** 쿠폰을 "사용 중"인 상태. 나머지 4개(CANCELLED/EXPIRED/PAYMENT_FAILED/REFUNDED)는 사용이 복원된다. */
    public static final List<OrderStatus> COUPON_ACTIVE = List.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
