package com.example.order.order;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT, PAID, PAYMENT_FAILED, EXPIRED, CANCELLED, SHIPPED, DELIVERED, REFUNDED;

    /** 쿠폰을 "사용 중"으로 집계하는 상태(R2.5). */
    public static final Set<OrderStatus> COUPON_ACTIVE =
            EnumSet.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
