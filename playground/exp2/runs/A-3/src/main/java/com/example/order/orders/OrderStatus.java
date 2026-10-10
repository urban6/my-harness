package com.example.order.orders;

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

    /** 쿠폰을 "사용 중"으로 보는 상태 (R2.6). */
    public static final Set<OrderStatus> USING_COUPON = EnumSet.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
