package com.example.order.service;

import com.example.order.domain.CouponType;

/** R2.4 할인 계산. 모든 금액은 long. */
public final class DiscountCalculator {

    private DiscountCalculator() {
    }

    /** discount = min(subtotal, min(maxDiscount, raw)) ; raw = FIXED ? value : floor(subtotal*value/100) */
    public static long discount(long subtotal, CouponType type, long value, Long maxDiscountAmount) {
        long raw = type == CouponType.FIXED ? value : Math.floorDiv(Math.multiplyExact(subtotal, value), 100L);
        long d = raw;
        if (maxDiscountAmount != null) {
            d = Math.min(d, maxDiscountAmount);
        }
        return Math.min(d, subtotal);
    }
}
