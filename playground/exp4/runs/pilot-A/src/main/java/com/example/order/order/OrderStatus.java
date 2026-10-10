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

    /** 쿠폰을 "사용 중"으로 치는 상태. 나머지는 쿠폰 사용이 복원된 상태다. */
    public static final Set<OrderStatus> COUPON_HOLDING =
            EnumSet.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
