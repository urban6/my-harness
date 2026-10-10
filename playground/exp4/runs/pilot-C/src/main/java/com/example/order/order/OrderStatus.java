package com.example.order.order;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT, PAID, SHIPPED, DELIVERED, PAYMENT_FAILED, EXPIRED, CANCELLED, REFUNDED;

    /** 쿠폰을 "사용 중"으로 점유하는 상태 (uk_orders_user_coupon_in_use 와 일치해야 한다). */
    public static final Set<OrderStatus> COUPON_IN_USE = EnumSet.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
