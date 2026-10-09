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

    /** 이 상태의 주문은 쿠폰을 "사용 중"이다 (R2.6). DB 부분 유니크 인덱스와 같은 집합이어야 한다. */
    public static final Set<OrderStatus> COUPON_IN_USE = EnumSet.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
