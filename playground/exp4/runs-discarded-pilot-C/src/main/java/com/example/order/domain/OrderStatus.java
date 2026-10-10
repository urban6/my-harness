package com.example.order.domain;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT, PAID, SHIPPED, DELIVERED, PAYMENT_FAILED, EXPIRED, CANCELLED, REFUNDED;

    /** 쿠폰을 "사용 중"으로 치는 상태 (uq_orders_active_coupon_user 의 술어와 동일). */
    public static final Set<OrderStatus> COUPON_ACTIVE =
            EnumSet.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
