package com.example.order.order;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED,
    SHIPPED,
    DELIVERED,
    REFUNDED;

    /** 쿠폰을 사용 중인 것으로 보는 상태. 그 밖의 상태로 바뀌면 쿠폰 사용이 복원된다. */
    public static final Set<OrderStatus> HOLDING_COUPON = EnumSet.of(PENDING_PAYMENT, PAID, SHIPPED, DELIVERED);
}
