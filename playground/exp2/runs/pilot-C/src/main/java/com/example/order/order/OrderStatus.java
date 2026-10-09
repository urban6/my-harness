package com.example.order.order;

import java.util.List;

public enum OrderStatus {
    PENDING_PAYMENT, PAID, PAYMENT_FAILED, EXPIRED, CANCELLED, REFUNDED, SHIPPED, DELIVERED;

    /** 쿠폰을 "사용 중"으로 보는 상태 집합. */
    public static final List<OrderStatus> COUPON_IN_USE = List.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
