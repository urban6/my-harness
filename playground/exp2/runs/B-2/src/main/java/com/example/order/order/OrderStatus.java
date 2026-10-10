package com.example.order.order;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED;

    /** 쿠폰을 사용 중인 상태 — 나머지 상태가 되면 쿠폰 사용이 복원된다(R2.6). */
    public static final Set<OrderStatus> COUPON_IN_USE = EnumSet.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
